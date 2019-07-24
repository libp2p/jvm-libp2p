package io.libp2p.core.mux

import io.libp2p.core.Connection
import io.libp2p.core.MUXER_SESSION
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.events.MuxSessionInitialized
import io.libp2p.core.util.netty.mux.AbtractMuxHandler
import io.libp2p.core.util.netty.mux.MuxChannel
import io.libp2p.core.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

class MuxHandler() : AbtractMuxHandler<ByteBuf>(), StreamMuxer.Session {

    private val idGenerator = AtomicLong(0xF)

    constructor(streamHandler: StreamHandler) : this() {
        this.streamHandler = streamHandler
    }

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        super.handlerAdded(ctx)
        ctx.channel().attr(MUXER_SESSION).set(this)
        ctx.fireUserEventTriggered(MuxSessionInitialized(this))
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MuxFrame
        when (msg.flag) {
            MuxFrame.Flag.OPEN -> onRemoteOpen(msg.id)
            MuxFrame.Flag.CLOSE -> onRemoteDisconnect(msg.id)
            MuxFrame.Flag.RESET -> onRemoteClose(msg.id)
            MuxFrame.Flag.DATA -> childRead(msg.id, msg.data!!)
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf): Boolean {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.DATA, data))
        return true
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.OPEN))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.CLOSE))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MuxFrame(child.id, MuxFrame.Flag.RESET))
    }

    override fun onRemoteCreated(child: MuxChannel<ByteBuf>) {
    }

    override fun generateNextId() = MuxId(idGenerator.incrementAndGet())

    override var streamHandler: StreamHandler? = null
        set(value) {
            field = value
            inboundInitializer = value!!.channelInitializer
        }

    private fun createStream(channel: MuxChannel<ByteBuf>) = Stream(channel, Connection(ctx!!.channel()))

    override fun createStream(streamHandler: StreamHandler): CompletableFuture<Stream> =
        newStream(streamHandler.channelInitializer).thenApply(::createStream)
}