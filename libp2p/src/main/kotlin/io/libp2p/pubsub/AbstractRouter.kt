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
import org.apache.logging.log4j.LogManager
import pubsub.pb.Rpc
import java.util.Collections.singletonList
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.function.BiConsumer
import java.util.function.Consumer

// 1 MB default max message size
const val DEFAULT_MAX_PUBSUB_MESSAGE_SIZE = 1 shl 20

typealias PubsubMessageHandler = (PubsubMessage) -> CompletableFuture<ValidationResult>

open class DefaultPubsubMessage(override val protobufMessage: Rpc.Message) : AbstractPubsubMessage() {
    override val messageId: MessageId = protobufMessage.from.toWBytes() + protobufMessage.seqno.toWBytes()
}

private val logger = LogManager.getLogger(AbstractRouter::class.java)

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

    protected open val peersTopics = mutableMultiBiMap<PeerHandler, Topic>()
    protected open val subscribedTopics = linkedSetOf<Topic>()
    protected open val pendingRpcParts = PendingRpcPartsMap<RpcPartsQueue> { DefaultRpcPartsQueue() }
    protected open val pendingMessagePromises = MultiSet<PeerHandler, CompletableFuture<Unit>>()

    protected class PendingRpcPartsMap<out TPartsQueue : RpcPartsQueue>(
        private val queueFactory: () -> TPartsQueue
    ) {
        private val map = linkedMapOf<PeerHandler, TPartsQueue>()

        val pendingPeers: Collection<PeerHandler> get() = map.keys.copy()

        fun getQueue(peer: PeerHandler) = map.computeIfAbsent(peer) { queueFactory() }
        fun popQueue(peer: PeerHandler) = map.remove(peer) ?: queueFactory()
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
        pendingRpcParts.getQueue(toPeer).addPublish(msg.protobufMessage)
        val sendPromise = CompletableFuture<Unit>()
        pendingMessagePromises[toPeer] += sendPromise
        return sendPromise
    }

    internal open fun validateMessageListLimits(msg: Rpc.RPCOrBuilder): Boolean {
        return true
    }

    /**
     * Flushes all pending message parts for all peers
     * @see addPendingRpcPart
     */
    protected fun flushAllPending() {
        pendingRpcParts.pendingPeers.forEach(::flushPending)
    }

    protected fun flushPending(peer: PeerHandler) {
        val peerMessages = pendingRpcParts.popQueue(peer).takeMerged()
        val allSendPromise = peerMessages.map { send(peer, it) }.thenApplyAll { }
        pendingMessagePromises.removeAll(peer)?.forEach {
            allSendPromise.forward(it)
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

    override fun onPeerActive(peer: PeerHandler) {
        val partsQueue = pendingRpcParts.getQueue(peer)
        subscribedTopics.forEach {
            partsQueue.addSubscribe(it)
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
            logger.debug("Dropping msg with lists exceeding limits from peer $peer")
            return
        }

        try {
            val subscriptions = msg.subscriptionsList.map { PubsubSubscription(it.topicid, it.subscribe) }
            subscriptionFilter
                .filterIncomingSubscriptions(subscriptions, peersTopics.getByFirst(peer))
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
                        res == ValidationResult.Ignore -> logger.trace("Ignoring pubsub message from peer $peer: ${it.first}")
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
        super.onPeerDisconnected(peer)
        peersTopics.removeAllByFirst(peer)
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
        activePeers.forEach { pendingRpcParts.getQueue(it).addSubscribe(topic) }
        subscribedTopics += topic
    }

    override fun unsubscribe(vararg topics: Topic) {
        runOnEventThread {
            topics.forEach(::unsubscribe)
            flushAllPending()
        }
    }

    protected open fun unsubscribe(topic: Topic) {
        activePeers.forEach { pendingRpcParts.getQueue(it).addUnsubscribe(topic) }
        subscribedTopics -= topic
    }

    override fun getPeerTopics(): CompletableFuture<Map<PeerId, Set<Topic>>> {
        return submitOnEventThread {
            peersTopics.asFirstToSecondMap().mapKeys { it.key.peerId }
        }
    }

    protected open fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
        return peer.writeAndFlush(msg)
    }

    override fun initHandler(handler: (PubsubMessage) -> CompletableFuture<ValidationResult>) {
        msgHandler = handler
    }
}
