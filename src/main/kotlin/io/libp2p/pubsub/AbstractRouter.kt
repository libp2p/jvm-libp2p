package io.libp2p.pubsub

import io.libp2p.core.BadPeerException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.MultiSet
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.copy
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVarInit
import io.libp2p.etc.types.toWBytes
import io.libp2p.etc.util.P2PServiceSemiDuplex
import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import org.apache.logging.log4j.LogManager
import pubsub.pb.Rpc
import java.util.Collections.singletonList
import java.util.Optional
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import java.util.function.Consumer

// 1 MB default max message size
const val DEFAULT_MAX_PUBSUB_MESSAGE_SIZE = 1 shl 20

open class DefaultPubsubMessage(override val protobufMessage: Rpc.Message) : AbstractPubsubMessage() {
    override val messageId: MessageId = protobufMessage.from.toWBytes() + protobufMessage.seqno.toWBytes()
}

/**
 * Implements common logic for pubsub routers
 */
abstract class AbstractRouter(
    val subscriptionFilter: TopicSubscriptionFilter,
    val maxMsgSize: Int = DEFAULT_MAX_PUBSUB_MESSAGE_SIZE
) : P2PServiceSemiDuplex(), PubsubRouter, PubsubRouterDebug {
    private val logger = LogManager.getLogger(AbstractRouter::class.java)

    override var curTimeMillis: () -> Long by lazyVarInit { { System.currentTimeMillis() } }
    override var random by lazyVarInit { Random() }
    override var name: String = "router"

    override var messageFactory: PubsubMessageFactory = { DefaultPubsubMessage(it) }
    var maxSeenMessagesLimit = 10000

    protected open val seenMessages: SeenCache<Optional<ValidationResult>> by lazy {
        LRUSeenCache(SimpleSeenCache(), maxSeenMessagesLimit)
    }

    private val peerTopics = MultiSet<PeerHandler, String>()
    private var msgHandler: (PubsubMessage) -> CompletableFuture<ValidationResult> = { RESULT_VALID }
    override var messageValidator = NOP_ROUTER_VALIDATOR

    val subscribedTopics = linkedSetOf<String>()
    val pendingRpcParts = linkedMapOf<PeerHandler, MutableList<Rpc.RPC>>()
    private var debugHandler: ChannelHandler? = null
    private val pendingMessagePromises = MultiSet<PeerHandler, CompletableFuture<Unit>>()

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
        addPendingRpcPart(toPeer, Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build())
        val sendPromise = CompletableFuture<Unit>()
        pendingMessagePromises[toPeer] += sendPromise
        return sendPromise
    }

    /**
     * Submits a partial message for a peer.
     * Later message parts for each peer are merged and sent to the wire
     */
    protected fun addPendingRpcPart(toPeer: PeerHandler, msgPart: Rpc.RPC) {
        pendingRpcParts.getOrPut(toPeer, { mutableListOf() }) += msgPart
    }

    /**
     * Drains all partial messages for [toPeer] and returns merged message
     */
    protected fun collectPeerMessage(toPeer: PeerHandler): Rpc.RPC? {
        val msgs = pendingRpcParts.remove(toPeer) ?: emptyList<Rpc.RPC>()
        if (msgs.isEmpty()) return null

        val bld = Rpc.RPC.newBuilder()
        msgs.forEach { bld.mergeFrom(it) }
        return bld.build()
    }

    /**
     * Flushes all pending message parts for all peers
     * @see addPendingRpcPart
     */
    protected fun flushAllPending() {
        pendingRpcParts.keys.copy().forEach(::flushPending)
    }

    protected fun flushPending(peer: PeerHandler) {
        collectPeerMessage(peer)?.also {
            val future = send(peer, it)
            pendingMessagePromises.removeAll(peer)?.forEach {
                future.forward(it)
            }
        }
    }

    override fun addPeer(peer: Stream) {
        addNewStream(peer)
    }

    override fun addPeerWithDebugHandler(peer: Stream, debugHandler: ChannelHandler?) {
        this.debugHandler = debugHandler
        try {
            addPeer(peer)
        } finally {
            this.debugHandler = null
        }
    }

    override fun initChannel(streamHandler: StreamHandler) {
        with(streamHandler.stream) {
            pushHandler(LimitedProtobufVarint32FrameDecoder(maxMsgSize))
            pushHandler(ProtobufVarint32LengthFieldPrepender())
            pushHandler(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
            pushHandler(ProtobufEncoder())
            debugHandler?.also { pushHandler(it) }
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

    override fun onPeerActive(peer: PeerHandler) {
        val helloPubsubMsg = Rpc.RPC.newBuilder().addAllSubscriptions(
            subscribedTopics.map {
                Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(it).build()
            }
        ).build()

        peer.writeAndFlush(helloPubsubMsg)
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
            logger.debug("Dropping msg with lists exceeding limits from peer $peer")
            return
        }

        try {
            val subscriptions = msg.subscriptionsList.map { PubsubSubscription(it.topicid, it.subscribe) }
            subscriptionFilter.filterIncomingSubscriptions(subscriptions, peerTopics[peer])
                .forEach { handleMessageSubscriptions(peer, it) }
        } catch (e: Exception) {
            logger.debug("Subscription filter error, ignoring message from peer $peer", e)
            return
        }

        if (msg.hasControl()) {
            processControl(msg.control, peer)
        }

        val (msgSubscribed, nonSubscribed) = msg.publishList
            .partition { it.topicIDsList.any { it in subscribedTopics } }

        nonSubscribed.forEach { notifyNonSubscribedMessage(peer, it) }

        val pMsgSubscribed = msgSubscribed.map { messageFactory(it) }
        val msgUnseen = pMsgSubscribed
            .filter { subscribedMessage ->
                val validationResult = seenMessages[subscribedMessage]
                if (validationResult != null) {
                    // Message has been seen
                    notifySeenMessage(peer, seenMessages.getSeenMessage(subscribedMessage), validationResult)
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
                logger.debug("Invalid pubsub message from peer $peer: $it", e)
                seenMessages[it] = Optional.of(ValidationResult.Invalid)
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
                Consumer { res ->
                    seenMessages[msg] = Optional.of(res)
                    if (res == ValidationResult.Invalid) notifyUnseenInvalidMessage(peer, msg)
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
                BiConsumer { res, err ->
                    when {
                        err != null -> logger.warn("Exception while handling message from peer $peer: ${it.first}", err)
                        res == ValidationResult.Invalid -> logger.debug("Invalid pubsub message from peer $peer: ${it.first}")
                        res == ValidationResult.Ignore -> logger.debug("Ingnoring pubsub message from peer $peer: ${it.first}")
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

    internal open fun validateMessageListLimits(msg: Rpc.RPC): Boolean {
        return true
    }

    private fun newValidatedMessages(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
        msgs.forEach { notifyUnseenValidMessage(receivedFrom, it) }
        broadcastInbound(msgs, receivedFrom)
    }

    override fun onPeerDisconnected(peer: PeerHandler) {
        super.onPeerDisconnected(peer)
        peerTopics.removeAll(peer)
    }

    override fun onPeerWireException(peer: PeerHandler?, cause: Throwable) {
        // exception occurred in protobuf decoders
        logger.debug("Malformed message from $peer : $cause")
        peer?.also { notifyMalformedMessage(it) }
    }

    override fun onServiceException(peer: PeerHandler?, msg: Any?, cause: Throwable) {
        if (cause is BadPeerException) {
            logger.debug("Remote peer ($peer) misbehaviour on message $msg: $cause")
        } else {
            logger.warn("AbstractRouter internal error on message $msg from peer $peer", cause)
        }
    }

    private fun handleMessageSubscriptions(peer: PeerHandler, msg: PubsubSubscription) {
        if (msg.subscribe) {
            peerTopics[peer] += msg.topic
        } else {
            peerTopics[peer] -= msg.topic
        }
    }

    protected fun getTopicsPeers(topics: Collection<String>) =
        activePeers.filter { topics.intersect(peerTopics[it]).isNotEmpty() }

    protected fun getTopicPeers(topic: String) =
        activePeers.filter { topic in peerTopics[it] }

    override fun subscribe(vararg topics: String) {
        runOnEventThread {
            topics.forEach(::subscribe)
            flushAllPending()
        }
    }

    protected open fun subscribe(topic: String) {
        activePeers.forEach {
            addPendingRpcPart(
                it,
                Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(topic)).build()
            )
        }
        subscribedTopics += topic
    }

    override fun unsubscribe(vararg topics: String) {
        runOnEventThread {
            topics.forEach(::unsubscribe)
            flushAllPending()
        }
    }

    protected open fun unsubscribe(topic: String) {
        activePeers.forEach {
            addPendingRpcPart(
                it,
                Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(false).setTopicid(topic)).build()
            )
        }
        subscribedTopics -= topic
    }

    override fun getPeerTopics(): CompletableFuture<Map<PeerId, Set<String>>> {
        return submitOnEventThread {
            val topicsByPeerId = hashMapOf<PeerId, Set<String>>()
            peerTopics.forEach { entry ->
                topicsByPeerId[entry.key.peerId] = HashSet(entry.value)
            }
            topicsByPeerId
        }
    }

    protected open fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
        return peer.writeAndFlush(msg)
    }

    override fun initHandler(handler: (PubsubMessage) -> CompletableFuture<ValidationResult>) {
        msgHandler = handler
    }
}
