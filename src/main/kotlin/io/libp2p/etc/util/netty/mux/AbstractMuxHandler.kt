package io.libp2p.etc.util.netty.mux

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.InternalErrorException
import io.libp2p.core.Libp2pException
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.hasCauseOfType
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture
import java.util.function.Function

typealias MuxChannelInitializer<TData> = (MuxChannel<TData>) -> Unit

private val log = LogManager.getLogger(AbstractMuxHandler::class.java)

abstract class AbstractMuxHandler<TData>() :
    ChannelInboundHandlerAdapter() {

    private val streamMap: MutableMap<MuxId, MuxChannel<TData>> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val activeFuture = CompletableFuture<Void>()
    private var closed = false
    protected abstract val inboundInitializer: MuxChannelInitializer<TData>
    private val pendingReadComplete = mutableSetOf<MuxId>()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        this.ctx = ctx
    }

    override fun channelActive(ctx: ChannelHandlerContext?) {
        activeFuture.complete(null)
        super.channelActive(ctx)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        activeFuture.completeExceptionally(ConnectionClosedException())
        closed = true
        super.channelUnregistered(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when {
            cause.hasCauseOfType(InternalErrorException::class) -> log.warn("Muxer internal error", cause)
            cause.hasCauseOfType(Libp2pException::class) -> log.debug("Muxer exception", cause)
            else -> log.warn("Unexpected exception", cause)
        }
    }

    fun getChannelHandlerContext(): ChannelHandlerContext {
        return ctx ?: throw InternalErrorException("Internal error: handler context should be initialized at this stage")
    }

    protected fun childRead(id: MuxId, msg: TData) {
        val child = streamMap[id] ?: throw ConnectionClosedException("Channel with id $id not opened")
        pendingReadComplete += id
        child.pipeline().fireChannelRead(msg)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        pendingReadComplete.forEach { streamMap[it]?.pipeline()?.fireChannelReadComplete() }
        pendingReadComplete.clear()
    }

    abstract fun onChildWrite(child: MuxChannel<TData>, data: TData): Boolean

    protected fun onRemoteOpen(id: MuxId) {
        val initializer = inboundInitializer
        val child = createChild(
            id,
            initializer,
            false
        )
        onRemoteCreated(child)
    }

    protected fun onRemoteDisconnect(id: MuxId) {
        // the channel could be RESET locally, so ignore remote CLOSE
        streamMap[id]?.onRemoteDisconnected()
    }

    protected fun onRemoteClose(id: MuxId) {
        // the channel could be RESET locally, so ignore remote RESET
        streamMap[id]?.closeImpl()
    }

    fun localDisconnect(child: MuxChannel<TData>) {
        onLocalDisconnect(child)
    }

    fun localClose(child: MuxChannel<TData>) {
        onLocalClose(child)
    }

    fun onClosed(child: MuxChannel<TData>) {
        streamMap.remove(child.id)
    }

    abstract override fun channelRead(ctx: ChannelHandlerContext, msg: Any)
    protected open fun onRemoteCreated(child: MuxChannel<TData>) {}
    protected abstract fun onLocalOpen(child: MuxChannel<TData>)
    protected abstract fun onLocalClose(child: MuxChannel<TData>)
    protected abstract fun onLocalDisconnect(child: MuxChannel<TData>)

    private fun createChild(
        id: MuxId,
        initializer: MuxChannelInitializer<TData>,
        initiator: Boolean
    ): MuxChannel<TData> {
        val child = MuxChannel(this, id, initializer, initiator)
        streamMap[id] = child
        ctx!!.channel().eventLoop().register(child)
        return child
    }

    // protected open fun createChannel(id: MuxId, initializer: ChannelHandler) = MuxChannel(this, id, initializer)

    protected abstract fun generateNextId(): MuxId

    fun newStream(outboundInitializer: MuxChannelInitializer<TData>): CompletableFuture<MuxChannel<TData>> {
        try {
            checkClosed() // if already closed then event loop is already down and async task may never execute
            return activeFuture.thenApplyAsync(
                Function {
                    checkClosed() // close may happen after above check and before this point
                    val child = createChild(
                        generateNextId(),
                        {
                            onLocalOpen(it)
                            outboundInitializer(it)
                        },
                        true
                    )
                    child
                },
                getChannelHandlerContext().channel().eventLoop()
            )
        } catch (e: Exception) {
            return completedExceptionally(e)
        }
    }

    private fun checkClosed() = if (closed) throw ConnectionClosedException("Can't create a new stream: connection was closed: " + ctx!!.channel()) else Unit
}
