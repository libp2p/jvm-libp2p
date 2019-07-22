package io.libp2p.core.util.netty.mux

import io.libp2p.core.Libp2pException
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture
import java.util.function.Function

abstract class AbtractMuxHandler<TData>(var inboundInitializer: ChannelHandler? = null) : ChannelInboundHandlerAdapter() {

    private val streamMap: MutableMap<MuxId, MuxChannel<TData>> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val activeFuture = CompletableFuture<Void>()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
//    }
//
//    override fun channelRegistered(ctx: ChannelHandlerContext) {
//        super.channelRegistered(ctx)
        this.ctx = ctx
    }

    override fun channelActive(ctx: ChannelHandlerContext?) {
        activeFuture.complete(null)
        super.channelActive(ctx)
    }

    fun getChannelHandlerContext(): ChannelHandlerContext {
        return ctx ?: throw Libp2pException("Internal error: handler context should be initialized at this stage")
    }

    protected fun childRead(id: MuxId, msg: TData) {
        val child = streamMap[id] ?: throw Libp2pException("Channel with id $id not opened")
        child.pipeline().fireChannelRead(msg)
    }

    abstract fun onChildWrite(child: MuxChannel<TData>, data: TData): Boolean

    protected fun onRemoteOpen(id: MuxId) {
        val initializer = inboundInitializer ?: throw Libp2pException("Illegal state: inbound stream handler is not set up yet")
        val child = createChild(id, initializer)
        onRemoteCreated(child)
    }

    protected fun onRemoteDisconnect(id: MuxId) {
        val child = streamMap[id] ?: throw Libp2pException("Channel with id $id not opened")
        child.onRemoteDisconnected()
    }

    protected fun onRemoteClose(id: MuxId) {
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

    protected open fun onRemoteCreated(child: MuxChannel<TData>) {}
    protected abstract fun onLocalOpen(child: MuxChannel<TData>)
    protected abstract fun onLocalClose(child: MuxChannel<TData>)
    protected abstract fun onLocalDisconnect(child: MuxChannel<TData>)

    private fun createChild(id: MuxId, initializer: ChannelHandler): MuxChannel<TData> {
        val child = MuxChannel(this, id, initializer)
        streamMap[id] = child
        ctx!!.channel().eventLoop().register(child)
        return child
    }

    open protected fun createChannel(id: MuxId, initializer: ChannelHandler)
            = MuxChannel(this, id, initializer)

    protected abstract fun generateNextId(): MuxId

    fun newStream(outboundInitializer: ChannelHandler): CompletableFuture<MuxChannel<TData>> {
        return activeFuture.thenApplyAsync(Function {
            val child = createChild(generateNextId(), nettyInitializer {
                onLocalOpen(it as MuxChannel<TData>)
                it.pipeline().addLast(outboundInitializer)
            })
            child
        }, getChannelHandlerContext().channel().eventLoop())
    }
}
