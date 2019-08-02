package io.libp2p.pubsub

import io.libp2p.core.Stream
import io.libp2p.core.types.LRUSet
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.toLongBigEndian
import io.libp2p.core.types.toVoidCompletableFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import org.apache.logging.log4j.LogManager
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

abstract class AbstractRouter : PubsubRouter {

    open inner class StreamHandler(val stream: Stream) : ChannelInboundHandlerAdapter() {
        lateinit var ctx: ChannelHandlerContext
        val topics = mutableSetOf<String>()

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            onInbound(this, msg as Rpc.RPC)
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            this.ctx = ctx
            onPeerActive(this)
        }
        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            onPeerDisconnected(this)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
            logger.warn("Unexpected error", cause)
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

    private var msgHandler: Consumer<Rpc.Message> = Consumer { }
    var maxSeenMessagesSizeSet = 10000
    var validator: PubsubMessageValidator = object : PubsubMessageValidator {}
    val peers = CopyOnWriteArrayList<StreamHandler>()
    val seenMessages by lazyVar { LRUSet.create<MessageUID>(maxSeenMessagesSizeSet) }
    val subscribedTopics = mutableSetOf<String>()
    val pendingRpcParts = mutableMapOf<StreamHandler, MutableList<Rpc.RPC>>()

    override fun publish(msg: Rpc.Message): CompletableFuture<Unit> {
        val rpcMsg = Rpc.RPC.newBuilder().addPublish(msg).build()
        return if (MessageUID(msg) in seenMessages) {
            CompletableFuture<Unit>().also { it.completeExceptionally(MessageAlreadySeenException("Msg: $msg")) }
        } else {
            validator.validate(rpcMsg) // check ourselves not to be a bad peer
            return broadcastOutbound(rpcMsg).thenApply {
                seenMessages.plus(MessageUID(msg))
                Unit
            }
        }
    }

    fun addPendingRpcPart(toPeer: StreamHandler, msgPart: Rpc.RPC)  {
        pendingRpcParts.getOrPut(toPeer, { mutableListOf() }) += msgPart
    }

    fun collectPeerMessage(toPeer: StreamHandler): Rpc.RPC? {
        val msgs = pendingRpcParts.remove(toPeer) ?: emptyList()
        if (msgs.isEmpty()) return null

        val bld = Rpc.RPC.newBuilder()
        msgs.forEach { bld.mergeFrom(it) }
        return bld.build()
    }

    fun flushAllPending() {
        pendingRpcParts.keys.toMutableList().forEach {peer ->
            collectPeerMessage(peer)?.also { send(peer, it) }
        }
    }

    override fun addPeer(peer: Stream) {
        peer.ch.pipeline().addLast(ProtobufDecoder(Rpc.RPC.getDefaultInstance()))
        peer.ch.pipeline().addLast(ProtobufEncoder())
        peer.ch.pipeline().addLast(createStreamHandler(peer))
    }

    override fun removePeer(peer: Stream) {
        peer.ch.close()
    }

    protected open fun createStreamHandler(stream: Stream): StreamHandler = StreamHandler((stream))

    // msg: validated unseen messages received from api
    protected abstract fun broadcastOutbound(msg: Rpc.RPC): CompletableFuture<Unit>

    // msg: validated unseen messages received from wire
    protected abstract fun broadcastInbound(msg: Rpc.RPC, receivedFrom: StreamHandler)

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
        val msgUnseen = filterSeen(msg)
        if (msgUnseen.publishCount > 0) {
            validator.validate(msgUnseen)
            msgUnseen.publishList.forEach(msgHandler)
            broadcastInbound(msgUnseen, peer)
            seenMessages.plus(msg.publishList.map { MessageUID(it) })
        }
    }

    private fun handleMessageSubscriptions(peer: StreamHandler, msg: Rpc.RPC.SubOpts) {
        if (msg.subscribe) {
            peer.topics += msg.topicid
        } else {
            peer.topics -= msg.topicid
        }
    }

    fun getTopicPeers(topic: String) = peers.filter { topic in it.topics }

    private fun filterSeen(msg: Rpc.RPC): Rpc.RPC =
        Rpc.RPC.newBuilder(msg)
            .clearPublish()
            .addAllPublish(msg.publishList.filter { MessageUID(it) !in seenMessages })
            .build()

    override fun subscribe(vararg topics: ByteArray) {
        topics.map { String(it) } .forEach(::subscribe)
    }

    protected open fun subscribe(topic: String) {
        peers.forEach { addPendingRpcPart(it,
                Rpc.RPC.newBuilder().addSubscriptions(Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(topic)).build()
            ) }
        subscribedTopics += topic
    }

    override fun unsubscribe(vararg topics: ByteArray) {
        topics.map { String(it) } .forEach(::unsubscribe)
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

    override fun setHandler(handler: Consumer<Rpc.Message>) {
        msgHandler = handler
    }

    companion object {
        val logger = LogManager.getLogger(AbstractRouter::class.java)
    }
}