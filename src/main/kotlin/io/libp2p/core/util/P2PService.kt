package io.libp2p.core.util

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.Stream
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.submitAsync
import io.libp2p.core.types.toVoidCompletableFuture
import io.libp2p.pubsub.AbstractRouter
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

abstract class P2PService {

    open inner class StreamHandler(val stream: Stream) : ChannelInboundHandlerAdapter() {
        var ctx: ChannelHandlerContext? = null
        var closed = false
        lateinit var peerHandler: PeerHandler

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            runOnEventThread {
                streamInbound(this, msg)
            }
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            this.ctx = ctx
            runOnEventThread {
                streamActive(this)
            }
        }
        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            closed = true
            runOnEventThread {
                this.ctx = null
                streamDisconnected(this)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable) {
            runOnEventThread {
                streamException(this, cause)
            }
        }
    }

    open inner class PeerHandler(val streamHandler: StreamHandler) {
        open fun peerId() = streamHandler.stream.remotePeerId()
        open fun writeAndFlush(msg: Any): CompletableFuture<Unit> = streamHandler.ctx!!.writeAndFlush(msg).toVoidCompletableFuture()
        open fun isActive() = streamHandler.ctx != null
    }

    var executor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor(threadFactory) }
    val peers = mutableListOf<PeerHandler>()
    val activePeers = mutableListOf<PeerHandler>()

    open fun addNewStream(stream: Stream) = runOnEventThread { addNewStreamEDT(stream) }

    protected fun addNewStreamEDT(stream: Stream) {
        val streamHandler = StreamHandler(stream)
        val peerHandler = createPeerHandler(streamHandler)
        streamHandler.peerHandler = peerHandler
        initChannel(streamHandler)
        peers += peerHandler
    }

    protected open fun createPeerHandler(streamHandler: StreamHandler) = PeerHandler(streamHandler)

    protected open fun streamActive(stream: StreamHandler) {
        activePeers += stream.peerHandler
        onPeerActive(stream.peerHandler)
    }

    protected open fun streamDisconnected(stream: StreamHandler) {
        activePeers -= stream.peerHandler
        if (peers.remove(stream.peerHandler)) {
            onPeerDisconnected(stream.peerHandler)
        }
    }

    protected open fun streamException(stream: StreamHandler, cause: Throwable) {
        onPeerException(stream.peerHandler, cause)
    }

    protected open fun streamInbound(stream: StreamHandler, msg: Any) {
        onInbound(stream.peerHandler, msg)
    }

    protected abstract fun initChannel(streamHandler: StreamHandler)

    protected abstract fun onPeerActive(peer: PeerHandler)

    protected abstract fun onPeerDisconnected(peer: PeerHandler)

    protected abstract fun onInbound(peer: PeerHandler, msg: Any)

    protected open fun onPeerException(peer: PeerHandler, cause: Throwable) {
        logger.warn("Error by peer $peer ", cause)
    }

    fun runOnEventThread(run: () -> Unit) = executor.execute(run)

    fun <C> submitOnEventThread(run: () -> CompletableFuture<C>): CompletableFuture<C> = executor.submitAsync(run)

    companion object {
        private val threadFactory = ThreadFactoryBuilder().setDaemon(true).setNameFormat("pubsub-router-event-thread-%d").build()
        val logger = LogManager.getLogger(AbstractRouter::class.java)
    }
}