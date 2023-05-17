package io.libp2p.etc.util

import io.libp2p.core.InternalErrorException
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.etc.types.submitAsync
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

private val logger = LoggerFactory.getLogger(P2PService::class.java)

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
 *
 * @param executor Executor backed by a single event thread
 * It is only safe to perform any service logic via this executor
 */
abstract class P2PService(
    protected val executor: ScheduledExecutorService
) {

    private val peersMutable = mutableListOf<PeerHandler>()
    /**
     * List of connected peers.
     * Note that connected peer could not be ready for writing yet, so consider [activePeers]
     * if any data is to be send
     */
    val peers: List<PeerHandler> = peersMutable

    private val activePeersMutable = mutableListOf<PeerHandler>()
    /**
     * List of active peers to which data could be written
     */
    val activePeers: List<PeerHandler> = activePeersMutable

    private val peerIdToPeerHandlerMapMutable = mutableMapOf<PeerId, PeerHandler>()

    /**
     * Maps [PeerId] to [PeerHandler] instance for connected peers
     */
    val peerIdToPeerHandlerMap: Map<PeerId, PeerHandler> = peerIdToPeerHandlerMapMutable

    /**
     * Represents a single stream
     */
    open inner class StreamHandler(val stream: Stream) : ChannelInboundHandlerAdapter() {
        var ctx: ChannelHandlerContext? = null
        var closed = false
        var aborted = false // indicates that stream was closed on init and [peerHandler] may not be initialized
        private var peerHandler: PeerHandler? = null

        override fun handlerAdded(ctx: ChannelHandlerContext?) {
            runOnEventThread {
                streamAdded(this)
            }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            runOnEventThread(peerHandler, msg) {
                try {
                    streamInbound(this, msg)
                } finally {
                    ReferenceCountUtil.release(msg)
                }
            }
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            this.ctx = ctx
            runOnEventThread(peerHandler) {
                streamActive(this)
            }
        }
        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            closed = true
            runOnEventThread(peerHandler) {
                this.ctx = null
                streamDisconnected(this)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable) {
            runOnEventThread(peerHandler) {
                streamException(this, cause)
            }
        }

        fun initPeerHandler(handler: PeerHandler) {
            peerHandler = handler
        }

        fun getPeerHandler() = peerHandler ?: throw InternalErrorException("[peerHandler] not initialized yet")

        /**
         * Close on stream initialize without setting the [peerHandler]
         */
        fun closeAbruptly() {
            aborted = true
            stream.close()
        }
    }

    /**
     * Represents a peer connection (which can have more than one underlying [Stream]s)
     * Use this handler's [writeAndFlush] instead of [StreamHandler.ctx] directly
     * to write data to the peer
     */
    open inner class PeerHandler(val streamHandler: StreamHandler) {
        open val peerId = streamHandler.stream.remotePeerId()
        open fun writeAndFlush(msg: Any): CompletableFuture<Unit> = streamHandler.ctx!!.writeAndFlush(msg).toVoidCompletableFuture()
        open fun isActive() = streamHandler.ctx != null
        open fun getInboundHandler(): StreamHandler? = streamHandler
        open fun getOutboundHandler(): StreamHandler? = streamHandler
        override fun toString(): String {
            return "PeerHandler(peerId=$peerId, stream=${streamHandler.stream})"
        }
    }

    /**
     * Adds a new stream to service. This method should **synchronously** init the underlying
     * [io.netty.channel.Channel]
     *
     * **Don't** initialize the channel on event thread! Any service logic related to adding a new stream
     * should be performed within [streamAdded] callback (which is invoked on event thread)
     */
    open fun addNewStream(stream: Stream) = initChannel(StreamHandler(stream))

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

    protected open fun streamAdded(streamHandler: StreamHandler) {
        val peerHandler = createPeerHandler(streamHandler)
        streamHandler.initPeerHandler(peerHandler)
        peersMutable += peerHandler
        peerIdToPeerHandlerMapMutable[peerHandler.peerId] = peerHandler
    }

    protected open fun createPeerHandler(streamHandler: StreamHandler) = PeerHandler(streamHandler)

    protected open fun streamActive(stream: StreamHandler) {
        if (stream.aborted) return
        activePeersMutable += stream.getPeerHandler()
        onPeerActive(stream.getPeerHandler())
    }

    protected open fun streamDisconnected(stream: StreamHandler) {
        if (stream.aborted) return
        val peerHandler = stream.getPeerHandler()
        activePeersMutable -= peerHandler
        if (peersMutable.remove(peerHandler)) {
            onPeerDisconnected(peerHandler)
        }
        peerIdToPeerHandlerMapMutable -= peerHandler.peerId
    }

    protected open fun streamException(stream: StreamHandler, cause: Throwable) {
        onPeerWireException(if (!stream.aborted) stream.getPeerHandler() else null, cause)
    }

    protected open fun streamInbound(stream: StreamHandler, msg: Any) {
        if (stream.aborted) return
        onInbound(stream.getPeerHandler(), msg)
    }

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
     * Notifies on error in peer wire communication
     * Invoked on event thread
     */
    protected open fun onPeerWireException(peer: PeerHandler?, cause: Throwable) {
        logger.warn("Error by peer $peer ", cause)
    }

    /**
     * Notifies on internal service error
     * @param peer optionally indicates which peer event caused error
     * @param msg optionally indicates what inbound message caused error
     */
    protected open fun onServiceException(peer: PeerHandler?, msg: Any?, cause: Throwable) {
        logger.warn("P2PService internal error on message $msg from peer $peer", cause)
    }

    /**
     * Executes the code on the service event thread
     */
    fun runOnEventThread(run: () -> Unit) = runOnEventThread(null, null, run)
    fun runOnEventThread(run: Runnable) = runOnEventThread(null, null) { run.run() }

    /**
     * Executes the code on the service event thread
     * Supply additional info which is reported to [onServiceException]
     */
    fun runOnEventThread(peer: PeerHandler? = null, msg: Any? = null, run: () -> Unit) = executor.execute {
        try {
            run()
        } catch (e: Exception) {
            onServiceException(peer, msg, e)
        }
    }

    /**
     * Executes the code on the service event thread
     */
    fun <C> submitOnEventThread(run: () -> C): CompletableFuture<C> = CompletableFuture.supplyAsync({ run() }, executor)
    /**
     * Executes the code on the service event thread
     */
    fun <C> submitAsyncOnEventThread(run: () -> CompletableFuture<C>): CompletableFuture<C> = executor.submitAsync(run)
}
