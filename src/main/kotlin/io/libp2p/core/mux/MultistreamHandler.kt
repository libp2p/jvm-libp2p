package io.libp2p.core.mux

import io.libp2p.core.Libp2pException
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * Created by Anton Nashatyrev on 09.07.2019.
 */

class MultistreamHandler(val childHandler: ChannelHandler) : ChannelInboundHandlerAdapter() {

    val streamMap: MutableMap<MultistreamId, MultistreamChannel2> = mutableMapOf()
    var ctx: ChannelHandlerContext? = null
    private val idCounter = AtomicLong()
    var isActive = false

    override fun channelRead(ctx: ChannelHandlerContext?, msg: Any?) {
        msg as MultistreamFrame
        val child = streamMap.getOrPut(msg.id) { createChild(msg.id) }
        child.pipeline().fireChannelRead(msg.data)
    }

    override fun channelActive(ctx: ChannelHandlerContext?) {
        isActive = true
        super.channelActive(ctx)
    }

    override fun channelRegistered(ctx: ChannelHandlerContext?) {
        this.ctx = ctx
        super.channelRegistered(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable) {
        cause.printStackTrace()
    }

    fun onChildWrite(child: MultistreamChannel2, data: ByteBuf): Boolean {
        ctx!!.writeAndFlush(MultistreamFrame(data, child.id))
        return true
    }

    private fun createChild(id: MultistreamId): MultistreamChannel2 {
        if (!isActive) throw Libp2pException("Creating child channels not supported yet when inactive")
        val ret = MultistreamChannel2(this, id)
        ctx!!.channel().eventLoop().register(ret)
        return ret
    }

    fun newStream(): CompletableFuture<MultistreamChannel2> {
        return CompletableFuture.completedFuture(createChild(MultistreamId(idCounter.incrementAndGet())))
    }

    fun createInboundStreamHandler(id: MultistreamId): ChannelHandler = childHandler
}