package io.libp2p.pubsub

import io.libp2p.core.BadPeerException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.MultiSet
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.copy
import io.libp2p.etc.types.createLRUMap
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVarInit
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.P2PServiceSemiDuplex
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import org.apache.logging.log4j.LogManager
import pubsub.pb.Rpc
import java.util.Collections.singletonList
import java.util.Optional
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import java.util.function.Consumer

typealias MessageId = String

/**
 * Implements common logic for pubsub routers
 */
abstract class AbstractRouter : P2PServiceSemiDuplex(), PubsubRouter, PubsubRouterDebug {
    private val logger = LogManager.getLogger(AbstractRouter::class.java)

    override var curTimeMillis: () -> Long by lazyVarInit { { System.currentTimeMillis() } }
    override var random by lazyVarInit { Random() }
    override var name: String = "router"
    var messageIdGenerator: (Rpc.Message) -> MessageId = { it.from.toByteArray().toHex() + it.seqno.toByteArray().toHex() }

    private val peerTopics = MultiSet<PeerHandler, String>()
    private var msgHandler: (Rpc.Message) -> CompletableFuture<ValidationResult> = { RESULT_VALID }
    var maxSeenMessagesSizeSet = 10000
    var validator: PubsubMessageValidator = PubsubMessageValidator.nopValidator()
    val seenMessages by lazy { createLRUMap<MessageId, Optional<ValidationResult>>(maxSeenMessagesSizeSet) }
    val subscribedTopics = linkedSetOf<String>()
    val pendingRpcParts = linkedMapOf<PeerHandler, MutableList<Rpc.RPC>>()
    private var debugHandler: ChannelHandler? = null
    private val pendingMessagePromises = MultiSet<PeerHandler, CompletableFuture<Unit>>()

    protected fun getMessageId(msg: Rpc.Message): MessageId = messageIdGenerator(msg)

    override fun publish(msg: Rpc.Message): CompletableFuture<Unit> {
        return submitAsyncOnEventThread {
            if (getMessageId(msg) in seenMessages) {
                completedExceptionally(MessageAlreadySeenException("Msg: $msg"))
            } else {
                validator.validate(msg) // check ourselves not to be a bad peer
                seenMessages[getMessageId(msg)] = Optional.of(ValidationResult.Valid)
                broadcastOutbound(msg)
            }
        }
    }

    protected open fun submitPublishMessage(toPeer: PeerHandler, msg: Rpc.Message): CompletableFuture<Unit> {
        addPendingRpcPart(toPeer, Rpc.RPC.newBuilder().addPublish(msg).build())
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
            pushHandler(ProtobufVarint32FrameDecoder())
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
    protected abstract fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit>

    /**
     * Broadcasts to peers validated unseen messages received from another peer
     */
    protected abstract fun broadcastInbound(msgs: List<Rpc.Message>, receivedFrom: PeerHandler)

    /**
     * Processes Pubsub control message
     */
    protected abstract fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler)

    override fun onPeerActive(peer: PeerHandler) {
        val helloPubsubMsg = Rpc.RPC.newBuilder().addAllSubscriptions(subscribedTopics.map {
            Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(it).build()
        }).build()

        peer.writeAndFlush(helloPubsubMsg)
    }

    protected open fun notifyMalformedMessage(peer: PeerHandler) {}
    protected open fun notifyUnseenMessage(peer: PeerHandler, msg: Rpc.Message) {}
    protected open fun notifyNonSubscribedMessage(peer: PeerHandler, msg: Rpc.Message) {}
    protected open fun notifySeenMessage(peer: PeerHandler, msg: Rpc.Message, validationResult: Optional<ValidationResult>) {}
    protected open fun notifyUnseenInvalidMessage(peer: PeerHandler, msg: Rpc.Message) {}
    protected open fun notifyUnseenValidMessage(peer: PeerHandler, msg: Rpc.Message) {}
    protected open fun acceptRequestsFrom(peer: PeerHandler) = true

    override fun onInbound(peer: PeerHandler, msg: Any) {
        if (!acceptRequestsFrom(peer)) return

        msg as Rpc.RPC
        msg.subscriptionsList.forEach { handleMessageSubscriptions(peer, it) }
        if (msg.hasControl()) {
            processControl(msg.control, peer)
        }
        val msgSubscribed = msg.publishList
            .filter { it.topicIDsList.any { it in subscribedTopics } }

        (msg.publishList - msgSubscribed).forEach { notifyNonSubscribedMessage(peer, it) }

        val msgUnseen = msgSubscribed
            .filter { subscribedMessage ->
                val messageId = getMessageId(subscribedMessage)
                val seenMessage = seenMessages[messageId]
                if (seenMessage != null) {
                    // Message has been seen
                    notifySeenMessage(peer, subscribedMessage, seenMessage)
                    false
                } else {
                    // Message is unseen
                    seenMessages[messageId] = Optional.empty()
                    notifyUnseenMessage(peer, subscribedMessage)
                    true
                }
            }

        val msgValid = msgUnseen.filter {
            try {
                validator.validate(it)
                true
            } catch (e: Exception) {
                logger.info("Invalid pubsub message from peer $peer: $it", e)
                seenMessages[getMessageId(it)] = Optional.of(ValidationResult.Invalid)
                notifyUnseenInvalidMessage(peer, it)
                false
            }
        }

        val validFuts = msgValid.map { it to msgHandler(it) }
        val doneUndone = validFuts.groupBy { it.second.isDone }
        val done = doneUndone.getOrDefault(true, emptyList())
        val undone = doneUndone.getOrDefault(false, emptyList())

        validFuts.forEach { (msg, validationFut) ->
            validationFut.thenAcceptAsync(Consumer { res ->
                seenMessages[getMessageId(msg)] = Optional.of(res)
                if (res == ValidationResult.Invalid) notifyUnseenInvalidMessage(peer, msg)
            }, executor)
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
            it.second.whenCompleteAsync(BiConsumer { res, err ->
                when {
                    err != null -> logger.warn("Exception while handling message from peer $peer: ${it.first}", err)
                    res == ValidationResult.Invalid -> logger.info("Invalid pubsub message from peer $peer: ${it.first}")
                    res == ValidationResult.Ignore -> logger.debug("Ingnoring pubsub message from peer $peer: ${it.first}")
                    else -> {
                        newValidatedMessages(singletonList(it.first), peer)
                        flushAllPending()
                    }
                }
            }, executor)
        }
    }

    private fun newValidatedMessages(msgs: List<Rpc.Message>, receivedFrom: PeerHandler) {
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

    private fun handleMessageSubscriptions(peer: PeerHandler, msg: Rpc.RPC.SubOpts) {
        if (msg.subscribe) {
            peerTopics[peer] += msg.topicid
        } else {
            peerTopics[peer] -= msg.topicid
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

    override fun initHandler(handler: (Rpc.Message) -> CompletableFuture<ValidationResult>) {
        msgHandler = handler
    }
}