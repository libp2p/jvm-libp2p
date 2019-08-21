package io.libp2p.core.util

import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.libp2p.core.Stream
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.submitAsync
import io.libp2p.core.types.toVoidCompletableFuture
import io.libp2p.pubsub.AbstractRouter
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.function.Supplier

/**
 * Base class for a service which manages many streams from different peers
 *
 * The service logic is expected to be complex, inbound messages/events from several
 * streams are expected to come on different threads and thus synchronisation could
 * be a severe problem. To handle this safely [P2PService] class supplies
 * [executor] backed by a single event thread where all the service logic should be processed
 * All stream callbacks are passed to this executor and all the client calls of the
 * service API should be executed on this thread to be thread-safe.
 * Consider using the following helpers [runOnEventThread], [submitOnEventThread], [submitAsyncOnEventThread]
 * or use the [executor] directly
 */
abstract class P2PService {

    /**
     * Represents a single stream
     */
    open inner class StreamHandler(val stream: Stream) : ChannelInboundHandlerAdapter() {
        var ctx: ChannelHandlerContext? = null
        var closed = false
        lateinit var peerHandler: PeerHandler

        override fun handlerAdded(ctx: ChannelHandlerContext?) {
            runOnEventThread {
                streamAdded(this)
            }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            runOnEventThread {
                try {
                    streamInbound(this, msg)
                } finally {
                    ReferenceCountUtil.release(msg)
                }
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

    /**
     * Represents a peer connection (which can have more than one underlying [Stream]s)
     * Use this handler's [writeAndFlush] instead of [StreamHandler.ctx] directly
     * to write data to the peer
     */
    open inner class PeerHandler(val streamHandler: StreamHandler) {
        open fun peerId() = streamHandler.stream.remotePeerId()
        open fun writeAndFlush(msg: Any): CompletableFuture<Unit> = streamHandler.ctx!!.writeAndFlush(msg).toVoidCompletableFuture()
        open fun isActive() = streamHandler.ctx != null
    }

    /**
     * Executor backed by a single event thread
     * It is only safe to perform any service logic via this executor
     *
     * The executor can be altered right after the instance creation.
     * Changing it later may have unpredictable results
     */
    var executor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor(threadFactory) }

    /**
     * List of connected peers.
     * Note that connected peer could not be ready for writing yet, so consider [activePeers]
     * if any data is to be send
     */
    val peers = mutableListOf<PeerHandler>()

    /**
     * List of active peers to which data could be written
     */
    val activePeers = mutableListOf<PeerHandler>()

    /**
     * Adds a new stream to service. This method should **synchronously** init the underlying
     * [io.netty.channel.Channel]
     *
     * **Don't** initialize the channel on event thread! Any service logic related to adding a new stream
     * should be performed within [streamAdded] callback (which is invoked on event thread)
     */
    open fun addNewStream(stream: Stream) = initChannel(StreamHandler(stream))

    protected open fun streamAdded(streamHandler: StreamHandler) {
        val peerHandler = createPeerHandler(streamHandler)
        streamHandler.peerHandler = peerHandler
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

    /**
     * Callback to initialize the [Stream] underlying [io.netty.channel.Channel]
     *
     * Is invoked **not** on the event thread
     * [io.netty.channel.Channel] initialization must be performed **synchronously on the caller thread**.
     * **Don't** initialize the channel on event thread!
     * Any service logic related to adding a new stream could be performed
     * within overridden [streamAdded] callback (which is invoked on event thread)
     */
    protected abstract fun initChannel(streamHandler: StreamHandler)

    /**
     * Callback notifies that the peer is active and ready for writing data
     * Invoked on event thread
     */
    protected abstract fun onPeerActive(peer: PeerHandler)

    /**
     * Callback notifies that the peer stream was disconnected
     * Invoked on event thread
     */
    protected abstract fun onPeerDisconnected(peer: PeerHandler)

    /**
     * New data from the peer
     * Invoked on event thread
     */
    protected abstract fun onInbound(peer: PeerHandler, msg: Any)

    /**
     * Notifies on error in peer communication
     * Invoked on event thread
     */
    protected open fun onPeerException(peer: PeerHandler, cause: Throwable) {
        logger.warn("Error by peer $peer ", cause)
    }

    /**
     * Executes the code on the service event thread
     */
    fun runOnEventThread(run: () -> Unit) = executor.execute(run)

    /**
     * Executes the code on the service event thread
     */
    fun <C> submitOnEventThread(run: () -> C): CompletableFuture<C> = CompletableFuture.supplyAsync(Supplier { run() }, executor)
    /**
     * Executes the code on the service event thread
     */
    fun <C> submitAsyncOnEventThread(run: () -> CompletableFuture<C>): CompletableFuture<C> = executor.submitAsync(run)

    companion object {
        private val threadFactory = ThreadFactoryBuilder().setDaemon(true).setNameFormat("P2PService-event-thread-%d").build()
        @JvmStatic
        protected val logger = LogManager.getLogger(AbstractRouter::class.java)
    }
}