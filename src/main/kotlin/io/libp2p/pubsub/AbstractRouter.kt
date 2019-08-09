package io.libp2p.pubsub

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.Stream
import io.libp2p.core.types.LRUSet
import io.libp2p.core.types.copy
import io.libp2p.core.types.forward
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.toLongBigEndian
import io.libp2p.core.types.toVoidCompletableFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import org.apache.logging.log4j.LogManager
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

abstract class AbstractRouter : PubsubRouter, PubsubRouterDebug {

    open inner class StreamHandler(val stream: Stream) : ChannelInboundHandlerAdapter() {
        lateinit var ctx: ChannelHandlerContext
        val topics = mutableSetOf<String>()

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            runOnEventThread {
                onInbound(this, msg as Rpc.RPC)
            }
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            this.ctx = ctx
            runOnEventThread {
                onPeerActive(this)
            }
        }
        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            runOnEventThread {
                onPeerDisconnected(this)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable) {
            runOnEventThread { onPeerException(this, cause) }
        }
    }

    data class MessageUID(val sender: ByteArray, val seqId: Long) {
        constructor(msg: Rpc.Message) : this(msg.from.toByteArray(), msg.seqno.toByteArray().toLongBigEndian())

        fun getGossipID(): String = "" + hashCode() // TODO

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MessageUID
            if (!sender.contentEquals(other.sender)) return false
            return seqId == other.seqId
        }

        override fun hashCode(): Int {
            var result = sender.contentHashCode()
            result = 31 * result + seqId.hashCode()
            return result
        }
    }

    override var executor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor(threadFactory) }
    override var curTime: () -> Long by lazyVar { { System.currentTimeMillis() } }
    override var random by lazyVar { Random() }

    private var msgHandler: (Rpc.Message) -> Unit = { }
    var maxSeenMessagesSizeSet = 10000
    var validator: PubsubMessageValidator = object : PubsubMessageValidator {}
    val peers = CopyOnWriteArrayList<StreamHandler>()
    val seenMessages by lazy { LRUSet.create<MessageUID>(maxSeenMessagesSizeSet) }
    val subscribedTopics = linkedSetOf<String>()
    val pendingRpcParts = linkedMapOf<StreamHandler, MutableList<Rpc.RPC>>()

    override fun publish(msg: Rpc.Message): CompletableFuture<Unit> {
        return submitOnEventThread {
            if (MessageUID(msg) in seenMessages) {
                CompletableFuture<Unit>().also { it.completeExceptionally(MessageAlreadySeenException("Msg: $msg")) }
            } else {
                validator.validate(msg) // check ourselves not to be a bad peer
                seenMessages += MessageUID(msg)
                broadcastOutbound(msg)
            }
        }
    }

    protected open fun submitPublishMessage(toPeer: StreamHandler, msg: Rpc.Message): CompletableFuture<Unit> {
        addPendingRpcPart(toPeer, Rpc.RPC.newBuilder().addPublish(msg).build())
        return CompletableFuture.completedFuture(null) // TODO
    }

    fun runOnEventThread(run: () -> Unit) {
        executor.execute(run)
    }

    fun <C> submitOnEventThread(run: () -> CompletableFuture<C>): CompletableFuture<C> {
        val ret = CompletableFuture<C>()
        executor.execute {
            run().forward(ret)
        }
        return ret
    }

    fun addPendingRpcPart(toPeer: StreamHandler, msgPart: Rpc.RPC)  {
        pendingRpcParts.getOrPut(toPeer, { mutableListOf() }) += msgPart
    }

    protected fun collectPeerMessage(toPeer: StreamHandler): Rpc.RPC? {
        val msgs = pendingRpcParts.remove(toPeer) ?: emptyList<Rpc.RPC>()
        if (msgs.isEmpty()) return null

        val bld = Rpc.RPC.newBuilder()
        msgs.forEach { bld.mergeFrom(it) }
        return bld.build()
    }

    protected fun flushAllPending() {
        pendingRpcParts.keys.copy().forEach {peer ->
            collectPeerMessage(peer)?.also { send(peer, it) }
        }
    }

    override fun addPeer(peer: Stream) {
        addPeerWithDebugHandler(peer, null)
    }

    override fun addPeerWithDebugHandler(peer: Stream, debugHandler: ChannelHandler?) {
        peer.ch.pipeline().addLast(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
        peer.ch.pipeline().addLast(ProtobufEncoder())
        debugHandler?.also { peer.ch.pipeline().addLast(it) }
        peer.ch.pipeline().addLast(createStreamHandler(peer))
    }

    override fun removePeer(peer: Stream) {
        peer.ch.close()
    }

    protected open fun createStreamHandler(stream: Stream): StreamHandler = StreamHandler((stream))

    // msg: validated unseen messages received from api
    protected abstract fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit>

    // msg: validated unseen messages received from wire
    protected abstract fun broadcastInbound(msg: Rpc.RPC, receivedFrom: StreamHandler)

    protected abstract fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: StreamHandler)

    protected open fun onPeerActive(peer: StreamHandler) {
        peers += peer
        val helloPubsubMsg = Rpc.RPC.newBuilder().addAllSubscriptions(subscribedTopics.map {
            Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(it).build()
        }).build()

        send(peer, helloPubsubMsg)
    }

    protected open fun onPeerDisconnected(peer: StreamHandler) {
        peers -= peer
    }

    private fun onInbound(peer: StreamHandler, msg: Rpc.RPC) {
        msg.subscriptionsList.forEach { handleMessageSubscriptions(peer, it) }
        if (msg.hasControl()) {
            processControl(msg.control, peer)
        }
        val msgUnseen = filterSeen(msg)
        if (msgUnseen.publishCount > 0) {
            validator.validate(msgUnseen)
            msgUnseen.publishList.forEach(msgHandler)
            seenMessages += msg.publishList.map { MessageUID(it) }
            broadcastInbound(msgUnseen, peer)
        }
    }

    protected fun onPeerException(peer: StreamHandler, cause: Throwable) {
        logger.warn("Error by peer $peer ", cause)
    }

    private fun handleMessageSubscriptions(peer: StreamHandler, msg: Rpc.RPC.SubOpts) {
        if (msg.subscribe) {
            peer.topics += msg.topicid
        } else {
            peer.topics -= msg.topicid
        }
    }

    protected fun getTopicsPeers(topics: Collection<String>) =
        peers.filter { topics.intersect(it.topics).isNotEmpty() }
    protected fun getTopicPeers(topic: String) =
        peers.filter { topic in it.topics }

    private fun filterSeen(msg: Rpc.RPC): Rpc.RPC =
        Rpc.RPC.newBuilder(msg)
            .clearPublish()
            .addAllPublish(msg.publishList.filter { MessageUID(it) !in seenMessages })
            .build()

    override fun subscribe(vararg topics: String) {
        runOnEventThread {
            topics.forEach(::subscribe)
            flushAllPending()
        }
    }

    protected open fun subscribe(topic: String) {
        peers.forEach { addPendingRpcPart(it,
                Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(topic)).build()
            ) }
        subscribedTopics += topic
    }

    override fun unsubscribe(vararg topics: String) {
        runOnEventThread {
            topics.forEach(::unsubscribe)
            flushAllPending()
        }
    }

    protected open  fun unsubscribe(topic: String) {
        peers.forEach { addPendingRpcPart(it,
            Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(false).setTopicid(topic)).build()
        ) }
        subscribedTopics -= topic
    }

    protected fun send(peer: StreamHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
        return peer.ctx.writeAndFlush(msg).toVoidCompletableFuture()
    }

    override fun setHandler(handler: (Rpc.Message) -> Unit) {
        msgHandler = handler
    }

    companion object {
        private val threadFactory = ThreadFactoryBuilder().setDaemon(true).setNameFormat("pubsub-router-event-thread-%d").build()
        val logger = LogManager.getLogger(AbstractRouter::class.java)
    }
}