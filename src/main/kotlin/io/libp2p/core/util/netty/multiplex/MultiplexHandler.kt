package io.libp2p.core.util.netty.multiplex

import io.libp2p.core.Libp2pException
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */

interface MultiplexHandlerIfc : ChannelInboundHandler {

    fun newStream(outboundInitializer: ChannelHandler): CompletableFuture<Unit>

    val inboundInitializer: ChannelHandler
}

abstract class MultiplexHandler<TData>(override val inboundInitializer: ChannelHandler) :
    ChannelInboundHandlerAdapter(), MultiplexHandlerIfc {

    private val streamMap: MutableMap<MultiplexId, MultiplexChannel<TData>> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val activeFuture = CompletableFuture<Void>()

    override fun channelRegistered(ctx: ChannelHandlerContext?) {
        this.ctx = ctx
        super.channelRegistered(ctx)
    }

    override fun channelActive(ctx: ChannelHandlerContext?) {
        activeFuture.complete(null)
        super.channelActive(ctx)
    }

    fun getChannelHandlerContext(): ChannelHandlerContext {
        return ctx ?: throw Libp2pException("Internal error: handler context should be initialized at this stage")
    }

    protected fun childRead(id: MultiplexId, msg: TData) {
        val child = streamMap[id] ?: throw Libp2pException("Channel with id $id not opened")
        child.pipeline().fireChannelRead(msg)
    }

    abstract fun onChildWrite(child: MultiplexChannel<TData>, data: TData): Boolean

    protected fun onRemoteOpen(id: MultiplexId) {
        streamMap[id] = createChild(id, inboundInitializer)
    }

    protected fun onRemoteClose(id: MultiplexId) {
        streamMap.remove(id)?.close()
    }
    protected abstract fun onLocalOpen(child: MultiplexChannel<TData>)
    abstract fun onLocalClose(child: MultiplexChannel<TData>)

    private fun createChild(id: MultiplexId, initializer: ChannelHandler): MultiplexChannel<TData> {
        val ret = MultiplexChannel(this, initializer, id)
        ctx!!.channel().eventLoop().register(ret)
        return ret
    }

    protected abstract fun generateNextId(): MultiplexId

    override fun newStream(outboundInitializer: ChannelHandler): CompletableFuture<Unit> {
        return activeFuture.thenApply {
            val child = createChild(generateNextId(), outboundInitializer)
            onLocalOpen(child)
        }
    }
}