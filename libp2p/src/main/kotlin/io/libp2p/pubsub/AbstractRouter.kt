package io.libp2p.pubsub

import io.libp2p.core.BadPeerException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.*
import io.libp2p.etc.util.P2PServiceSemiDuplex
import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import org.slf4j.LoggerFactory
import pubsub.pb.Rpc
import java.time.Duration
import java.util.Collections.singletonList
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeoutException

// 1 MB default max message size
const val DEFAULT_MAX_PUBSUB_MESSAGE_SIZE = 1 shl 20
val DEFAULT_OUTBOUND_WRITE_PROGRESS_TIMEOUT: Duration = Duration.ofSeconds(30)

typealias PubsubMessageHandler = (PubsubMessage) -> CompletableFuture<ValidationResult>

open class DefaultPubsubMessage(override val protobufMessage: Rpc.Message) : AbstractPubsubMessage() {
    override val messageId: MessageId = protobufMessage.from.toWBytes() + protobufMessage.seqno.toWBytes()
}

private val logger = LoggerFactory.getLogger(AbstractRouter::class.java)

/**
 * Implements common logic for pubsub routers
 */
abstract class AbstractRouter(
    executor: ScheduledExecutorService,
    override val protocol: PubsubProtocol,
    protected val subscriptionFilter: TopicSubscriptionFilter,
    protected val maxMsgSize: Int,
    override val messageFactory: PubsubMessageFactory,
    protected val seenMessages: SeenCache<Optional<ValidationResult>>,
    protected val messageValidator: PubsubRouterMessageValidator
) : P2PServiceSemiDuplex(executor), PubsubRouter, PubsubRouterDebug {

    protected var msgHandler: PubsubMessageHandler = { throw IllegalStateException("Message handler is not initialized for PubsubRouter") }
    protected var outboundWriteProgressTimeout: Duration = DEFAULT_OUTBOUND_WRITE_PROGRESS_TIMEOUT
    internal var outboundLimits: PubsubOutboundLimits = PubsubOutboundLimits.UNBOUNDED
        private set

    protected open val peersTopics = mutableMultiBiMap<PeerHandler, Topic>()
    protected open val subscribedTopics = linkedSetOf<Topic>()
    protected open val pendingRpcParts = PendingRpcPartsMap<RpcPartsQueue> { DefaultRpcPartsQueue() }
    private val outboundSendStates = mutableMapOf<PeerHandler, OutboundSendState>()
    private val stalledOutboundPeers = mutableMapOf<PeerHandler, Throwable>()

    internal fun configureOutboundLimits(limits: PubsubOutboundLimits) {
        outboundLimits = limits
    }

    private class ActiveGeneration(
        val promises: List<CompletableFuture<Unit>>,
        val usage: OutboundResourceUsage
    ) {
        var messages: List<Rpc.RPC> = emptyList()
        var messageIndex: Int = 0
    }

    private class OutboundSendState {
        var activeGeneration: ActiveGeneration? = null
        var activeWrite: CompletableFuture<Unit>? = null
        var progressDeadline: ScheduledFuture<*>? = null
    }

    protected fun hasOutboundState(): Boolean =
        outboundSendStates.isNotEmpty() ||
            stalledOutboundPeers.isNotEmpty() ||
            pendingRpcParts.pendingPeers.isNotEmpty()

    protected class PendingRpcPartsMap<out TPartsQueue : RpcPartsQueue>(
        private val queueFactory: () -> TPartsQueue
    ) {
        data class PendingGeneration<out TPartsQueue : RpcPartsQueue>(
            val queue: TPartsQueue,
            val promises: MutableList<CompletableFuture<Unit>> = mutableListOf(),
            var usage: OutboundResourceUsage = OutboundResourceUsage.ZERO
        )

        private val map = linkedMapOf<PeerHandler, PendingGeneration<TPartsQueue>>()

        val pendingPeers: Collection<PeerHandler> get() = map.keys.copy()

        fun get(peer: PeerHandler): PendingGeneration<TPartsQueue> =
            map.computeIfAbsent(peer) { PendingGeneration(queueFactory()) }

        fun getOrNull(peer: PeerHandler) = map[peer]
        fun pop(peer: PeerHandler) = map.remove(peer)
    }

    override fun publish(msg: PubsubMessage): CompletableFuture<Unit> {
        return submitAsyncOnEventThread {
            if (msg in seenMessages) {
                completedExceptionally(MessageAlreadySeenException("Msg: $msg"))
            } else {
                messageValidator.validate(msg) // check ourselves not to be a bad peer
                seenMessages[msg] = Optional.of(ValidationResult.Valid)
                broadcastOutbound(msg)
            }
        }
    }

    protected open fun submitPublishMessage(toPeer: PeerHandler, msg: PubsubMessage): CompletableFuture<Unit> {
        val rpc = Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        if (rpc.serializedSize > maxMsgSize) {
            return completedExceptionally(
                InvalidMessageException(
                    "Outbound pubsub message size ${rpc.serializedSize} exceeds maxGossipMessageSize $maxMsgSize"
                )
            )
        }
        val sendPromise = CompletableFuture<Unit>()
        val cause = enqueueOutbound(
            toPeer,
            pendingRpcParts,
            OutboundResourceUsage.fromRpc(rpc, promiseEntries = 1),
            sendPromise
        ) { it.addPublish(msg.protobufMessage) }
        cause?.let(sendPromise::completeExceptionally)
        return sendPromise
    }

    protected fun <TPartsQueue : RpcPartsQueue> enqueueOutbound(
        peer: PeerHandler,
        pendingMap: PendingRpcPartsMap<TPartsQueue>,
        usage: OutboundResourceUsage,
        promise: CompletableFuture<Unit>? = null,
        addPart: (TPartsQueue) -> Unit
    ): Throwable? {
        stalledOutboundPeers[peer]?.let { return it }
        val existingState = outboundSendStates[peer]
        val pending = pendingMap.get(peer)
        val activeUsage = existingState?.activeGeneration?.usage ?: OutboundResourceUsage.ZERO
        val retained = activeUsage.plusOrNull(pending.usage)
        val projected = retained?.plusOrNull(usage)

        if (projected == null || !projected.fits(outboundLimits)) {
            val cause = OutboundQueueOverflowException(
                "Outbound pubsub queue for ${peer.peerId} exceeds " +
                    "${outboundLimits.maxRetainedBytesPerPeer} bytes or " +
                    "${outboundLimits.maxRetainedEntriesPerPeer} entries"
            )
            cleanupOutbound(peer, cause, resetStream = true)
            return cause
        }

        try {
            addPart(pending.queue)
        } catch (error: Throwable) {
            if (pending.usage == OutboundResourceUsage.ZERO &&
                pending.promises.isEmpty()
            ) {
                pendingMap.pop(peer)
            }
            throw error
        }
        pending.usage = pending.usage.plusOrNull(usage)!!
        promise?.let(pending.promises::add)
        val state = existingState ?: OutboundSendState().also { outboundSendStates[peer] = it }
        ensureProgressDeadline(peer, state)
        return null
    }

    internal open fun validateMessageListLimits(msg: Rpc.RPCOrBuilder): Boolean {
        return true
    }

    /**
     * Per-router caps on repeated-field counts inside inbound RPCs. Enforced before
     * protobuf materialisation by an [RpcCountFrameDecoder] inserted into the stream
     * pipeline. Defaults to [PubsubRpcLimits.NONE] (no pre-decode cap). Subclasses
     * with configured limits (e.g. [io.libp2p.pubsub.gossip.GossipRouter]) override.
     */
    protected open val rpcLimits: PubsubRpcLimits
        get() = PubsubRpcLimits.NONE

    /**
     * Flushes all pending message parts for all peers
     */
    protected fun flushAllPending() {
        pendingRpcParts.pendingPeers.forEach(::flushPending)
    }

    protected fun flushPending(peer: PeerHandler) {
        if (stalledOutboundPeers.containsKey(peer)) {
            failQueuedOutbound(peer, stalledOutboundPeers.getValue(peer))
            return
        }
        val state = outboundSendStates.getOrPut(peer) { OutboundSendState() }
        pumpOutbound(peer, state)
    }

    protected open fun isOutboundWritable(peer: PeerHandler): Boolean =
        peer.isWritable()

    override fun streamWritabilityChanged(stream: StreamHandler) {
        if (stream.aborted || stream.closed) return
        val peer = stream.getPeerHandler()
        if (peer.getOutboundHandler() === stream) {
            outboundSendStates[peer]?.let { pumpOutbound(peer, it) }
        }
    }

    override fun addPeer(peer: Stream) = addPeerWithDebugHandler(peer, null)
    override fun addPeerWithDebugHandler(peer: Stream, debugHandler: ChannelHandler?) {
        addNewStreamWithHandler(peer, debugHandler)
    }

    override fun addNewStream(stream: Stream) = addNewStreamWithHandler(stream, null)
    protected fun addNewStreamWithHandler(stream: Stream, handler: ChannelHandler?) {
        initChannelWithHandler(StreamHandler(stream), handler)
    }

    override fun initChannel(streamHandler: StreamHandler) = initChannelWithHandler(streamHandler, null)
    protected open fun initChannelWithHandler(streamHandler: StreamHandler, handler: ChannelHandler?) {
        with(streamHandler.stream) {
            pushHandler(LimitedProtobufVarint32FrameDecoder(maxMsgSize))
            pushHandler(ProtobufVarint32LengthFieldPrepender())
            pushHandler(RpcCountFrameDecoder(rpcLimits))
            pushHandler(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
            pushHandler(ProtobufEncoder())
            handler?.also { pushHandler(it) }
            pushHandler(streamHandler)
        }
    }

    override fun removePeer(peer: Stream) {
        peer.close()
    }

    /**
     * Broadcasts to peers validated unseen messages received from api
     */
    protected abstract fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit>

    /**
     * Broadcasts to peers validated unseen messages received from another peer
     */
    protected abstract fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler)

    /**
     * Processes Pubsub control message
     */
    protected abstract fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler)

    /**
     * Processes Gossipsub extensions messages
     */
    protected abstract fun processExtensions(msg: Rpc.RPC, receivedFrom: PeerHandler)

    override fun onPeerActive(peer: PeerHandler) {
        subscribedTopics.forEach {
            enqueueSubscription(peer, it, RpcPartsQueue.SubscriptionStatus.Subscribed)
        }
        flushPending(peer)
    }

    protected open fun notifyMalformedMessage(peer: PeerHandler) {}
    protected open fun notifyUnseenMessage(peer: PeerHandler, msg: PubsubMessage) {}
    protected open fun notifyNonSubscribedMessage(peer: PeerHandler, msg: Rpc.Message) {}
    protected open fun notifySeenMessage(peer: PeerHandler, msg: PubsubMessage, validationResult: Optional<ValidationResult>) {}
    protected open fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: PubsubMessage) {}
    protected open fun notifyUnseenValidMessage(peer: PeerHandler, msg: PubsubMessage) {}
    protected open fun acceptRequestsFrom(peer: PeerHandler) = true

    override fun onInbound(peer: PeerHandler, msg: Any) {
        if (!acceptRequestsFrom(peer)) return

        msg as Rpc.RPC

        // Validate message
        if (!validateMessageListLimits(msg)) {
            logger.debug("Dropping msg with lists exceeding limits from peer {}", peer)
            return
        }

        try {
            val subscriptions = msg.subscriptionsList.map { PubsubSubscription(it.topicid, it.subscribe) }
            subscriptionFilter
                .filterIncomingSubscriptions(subscriptions, peersTopics.getByFirst(peer))
                .forEach { handleMessageSubscriptions(peer, it) }
        } catch (e: Exception) {
            logger.debug("Subscription filter error, ignoring message from peer {}", peer, e)
            return
        }

        if (msg.hasControl()) {
            processControl(msg.control, peer)
        }

        if (protocol.supportsExtensions()) {
            processExtensions(msg, peer)
        }

        val (msgSubscribed, nonSubscribed) = msg.publishList
            .partition { rpcMsg -> rpcMsg.topicIDsList.any { it in subscribedTopics } }

        nonSubscribed.forEach { notifyNonSubscribedMessage(peer, it) }

        val pMsgSubscribed = msgSubscribed.map { messageFactory(it) }
        val msgUnseen = pMsgSubscribed
            .filter { subscribedMessage ->
                val validationResult = seenMessages[subscribedMessage]
                if (validationResult != null) {
                    // Message has been seen
                    notifySeenMessage(peer, seenMessages.getSeenMessageCached(subscribedMessage), validationResult)
                    false
                } else {
                    // Message is unseen
                    seenMessages[subscribedMessage] = Optional.empty()
                    notifyUnseenMessage(peer, subscribedMessage)
                    true
                }
            }

        val msgValid = msgUnseen.filter {
            try {
                messageValidator.validate(it)
                true
            } catch (e: Exception) {
                logger.debug("Invalid pubsub message from peer {}: {}", peer, it, e)
                // Avoid rejecting a future legitimate message with the same id
                // (e.g. same from||seqno)
                seenMessages -= it.messageId
                notifyUnseenInvalidMessage(peer, it)
                false
            }
        }

        val validFuts = msgValid.map { it to msgHandler(it) }
        val doneUndone = validFuts.groupBy { it.second.isDone }
        val done = doneUndone.getOrDefault(true, emptyList())
        val undone = doneUndone.getOrDefault(false, emptyList())

        validFuts.forEach { (msg, validationFut) ->
            validationFut.thenAcceptAsync(
                { res ->
                    if (res == ValidationResult.Invalid) {
                        // Evict so a later legitimate message with the same id is not
                        // suppressed by this rejected one.
                        seenMessages -= msg.messageId
                        notifyUnseenInvalidMessage(peer, msg)
                    } else {
                        seenMessages[msg] = Optional.of(res)
                    }
                },
                executor
            )
        }

        // broadcasting in a single chunk those which were validated synchronously
        val validatedMsgs = done.filter {
            try {
                it.second.get() == ValidationResult.Valid
            } catch (e: Exception) {
                logger.warn("Exception while handling message from peer $peer: ${it.first}", e)
                false
            }
        }
            .map { it.first }
        newValidatedMessages(validatedMsgs, peer)
        flushAllPending()

        // broadcast others on completion
        undone.forEach {
            it.second.whenCompleteAsync(
                { res, err ->
                    when {
                        err != null -> logger.warn("Exception while handling message from peer $peer: ${it.first}", err)
                        res == ValidationResult.Invalid -> logger.debug(
                            "Invalid pubsub message from peer {}: {}",
                            peer,
                            it.first
                        )
                        res == ValidationResult.Ignore -> logger.trace(
                            "Ignoring pubsub message from peer {}: {}",
                            peer,
                            it.first
                        )
                        else -> {
                            newValidatedMessages(singletonList(it.first), peer)
                            flushAllPending()
                        }
                    }
                },
                executor
            )
        }
    }

    private fun newValidatedMessages(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
        msgs.forEach { notifyUnseenValidMessage(receivedFrom, it) }
        broadcastInbound(msgs, receivedFrom)
    }

    override fun onPeerDisconnected(peer: PeerHandler) {
        cleanupOutbound(peer, io.libp2p.core.ConnectionClosedException(), resetStream = false)
        stalledOutboundPeers -= peer
        super.onPeerDisconnected(peer)
        peersTopics.removeAllByFirst(peer)
    }

    override fun onPeerWireException(peer: PeerHandler?, cause: Throwable) {
        // exception occurred in protobuf decoders
        logger.debug("Malformed message from {} : {}", peer, cause)
        peer?.also { notifyMalformedMessage(it) }
    }

    override fun onServiceException(peer: PeerHandler?, msg: Any?, cause: Throwable) {
        if (cause is BadPeerException) {
            logger.debug("Remote peer ({}) misbehaviour on message {} : {}", peer, msg, cause)
        } else {
            logger.warn("AbstractRouter internal error on message {} from peer {}", msg, peer, cause)
        }
    }

    private fun handleMessageSubscriptions(peer: PeerHandler, msg: PubsubSubscription) {
        if (msg.subscribe) {
            peersTopics.add(peer, msg.topic)
        } else {
            peersTopics.remove(peer, msg.topic)
        }
    }

    protected fun getTopicPeers(topic: Topic) = peersTopics.getBySecond(topic)

    override fun subscribe(vararg topics: Topic) {
        runOnEventThread {
            topics.forEach(::subscribe)
            flushAllPending()
        }
    }

    protected open fun subscribe(topic: Topic) {
        activePeers.forEach {
            enqueueSubscription(it, topic, RpcPartsQueue.SubscriptionStatus.Subscribed)
        }
        subscribedTopics += topic
    }

    override fun unsubscribe(vararg topics: Topic) {
        runOnEventThread {
            topics.forEach(::unsubscribe)
            flushAllPending()
        }
    }

    protected open fun unsubscribe(topic: Topic) {
        activePeers.forEach {
            enqueueSubscription(it, topic, RpcPartsQueue.SubscriptionStatus.Unsubscribed)
        }
        subscribedTopics -= topic
    }

    private fun enqueueSubscription(
        peer: PeerHandler,
        topic: Topic,
        status: RpcPartsQueue.SubscriptionStatus
    ) {
        val subscription = Rpc.RPC.SubOpts.newBuilder()
            .setTopicid(topic)
            .setSubscribe(status == RpcPartsQueue.SubscriptionStatus.Subscribed)
            .build()
        val rpc = Rpc.RPC.newBuilder().addSubscriptions(subscription).build()
        enqueueOutbound(
            peer,
            pendingRpcParts,
            OutboundResourceUsage.fromRpc(rpc)
        ) { it.addSubscription(topic, status) }
    }

    override fun getPeerTopics(): CompletableFuture<Map<PeerId, Set<Topic>>> {
        return submitOnEventThread {
            peersTopics.asFirstToSecondMap()
                .map { (key, value) ->
                    key.peerId to value.toSet()
                }
                .toMap()
        }
    }

    protected open fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
        return peer.writeAndFlush(msg)
    }

    protected fun enqueueRpc(peer: PeerHandler, msg: Rpc.RPC) {
        if (msg.serializedSize > maxMsgSize) {
            throw InvalidMessageException(
                "Outbound RPC size ${msg.serializedSize} exceeds maxGossipMessageSize $maxMsgSize"
            )
        }
        val cause = enqueueOutbound(
            peer,
            pendingRpcParts,
            OutboundResourceUsage.fromRpc(msg)
        ) { it.addRpc(msg) }
        if (cause == null) flushPending(peer)
    }

    private fun pumpOutbound(
        peer: PeerHandler,
        state: OutboundSendState
    ) {
        if (outboundSendStates[peer] !== state || state.activeWrite != null) return

        var generation = state.activeGeneration
        if (generation == null || generation.messageIndex >= generation.messages.size) {
            if (generation != null) {
                completeActiveGeneration(state)
            }
            if (pendingRpcParts.getOrNull(peer) == null) {
                removeIdleState(peer, state)
                return
            }
            if (!isOutboundWritable(peer)) {
                ensureProgressDeadline(peer, state)
                return
            }
            val pending = pendingRpcParts.pop(peer)!!
            generation = ActiveGeneration(pending.promises.toList(), pending.usage)
            state.activeGeneration = generation
            generation.messages = try {
                pending.queue.takeMerged()
            } catch (cause: Throwable) {
                cleanupOutbound(peer, cause, resetStream = true)
                return
            }
            if (generation.messages.isEmpty()) {
                completeActiveGeneration(state)
                removeIdleState(peer, state)
                return
            }
        }

        if (!isOutboundWritable(peer)) {
            ensureProgressDeadline(peer, state)
            return
        }

        startWrite(peer, state, generation.messages[generation.messageIndex])
    }

    private fun removeIdleState(peer: PeerHandler, state: OutboundSendState) {
        if (state.activeWrite == null &&
            state.activeGeneration == null &&
            pendingRpcParts.getOrNull(peer) == null &&
            outboundSendStates.remove(peer, state)
        ) {
            state.progressDeadline?.cancel(false)
            state.progressDeadline = null
        }
    }

    private fun hasRetainedWork(
        peer: PeerHandler,
        state: OutboundSendState
    ): Boolean =
        state.activeWrite != null ||
            state.activeGeneration?.let { it.messageIndex < it.messages.size } == true ||
            pendingRpcParts.getOrNull(peer) != null

    private fun ensureProgressDeadline(peer: PeerHandler, state: OutboundSendState) {
        if (state.progressDeadline != null) return
        state.progressDeadline =
            scheduleOnEventThread(outboundWriteProgressTimeout, peer) {
                if (outboundSendStates[peer] === state && hasRetainedWork(peer, state)) {
                    state.progressDeadline = null
                    cleanupOutbound(
                        peer,
                        TimeoutException(
                            "Outbound pubsub write to ${peer.peerId} made no progress within $outboundWriteProgressTimeout"
                        ),
                        resetStream = true
                    )
                }
            }
    }

    private fun startWrite(
        peer: PeerHandler,
        state: OutboundSendState,
        msg: Rpc.RPC
    ) {
        val write = try {
            send(peer, msg)
        } catch (cause: Throwable) {
            completedExceptionally(cause)
        }
        state.activeWrite = write
        ensureProgressDeadline(peer, state)
        write.whenComplete { _, error ->
            runOnEventThread(peer) {
                if (outboundSendStates[peer] !== state || state.activeWrite !== write) return@runOnEventThread

                state.progressDeadline?.cancel(false)
                state.progressDeadline = null
                state.activeWrite = null

                if (error != null) {
                    cleanupOutbound(peer, error, resetStream = true)
                } else {
                    state.activeGeneration!!.messageIndex++
                    if (hasRetainedWork(peer, state)) {
                        ensureProgressDeadline(peer, state)
                    }
                    pumpOutbound(peer, state)
                }
            }
        }
    }

    private fun completeActiveGeneration(state: OutboundSendState) {
        val generation = state.activeGeneration ?: return
        generation.promises.forEach { it.complete(Unit) }
        state.activeGeneration = null
    }

    private fun cleanupOutbound(peer: PeerHandler, cause: Throwable, resetStream: Boolean) {
        outboundSendStates.remove(peer)?.let { state ->
            state.progressDeadline?.cancel(false)
            state.progressDeadline = null
            state.activeWrite?.completeExceptionally(cause)
            state.activeWrite = null
            state.activeGeneration?.promises?.forEach { it.completeExceptionally(cause) }
            state.activeGeneration = null
        }
        failQueuedOutbound(peer, cause)

        if (resetStream) {
            stalledOutboundPeers[peer] = cause
            resetOutboundStream(peer)
        }
    }

    protected open fun resetOutboundStream(peer: PeerHandler) {
        peer.getOutboundHandler()?.stream?.reset()
    }

    private fun failQueuedOutbound(peer: PeerHandler, cause: Throwable) {
        pendingRpcParts.pop(peer)?.let { pending ->
            pending.promises.forEach { it.completeExceptionally(cause) }
            pending.promises.clear()
            pending.usage = OutboundResourceUsage.ZERO
        }
    }

    override fun initHandler(handler: (PubsubMessage) -> CompletableFuture<ValidationResult>) {
        msgHandler = handler
    }
}
