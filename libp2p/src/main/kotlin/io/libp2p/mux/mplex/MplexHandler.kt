package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.types.sliceMaxSize
import io.libp2p.etc.util.netty.mux.MuxChannel
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxFrame
import io.libp2p.mux.MuxHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

open class MplexHandler(
    override val multistreamProtocol: MultistreamProtocol,
    override val maxFrameDataLength: Int,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>
) : MuxHandler(ready, inboundStreamHandler) {

    private val idGenerator = AtomicLong(0xF)

    override fun generateNextId() =
        MuxId(getChannelHandlerContext().channel().id(), idGenerator.incrementAndGet(), true)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MuxFrame
        when (msg.flag) {
            MuxFrame.Flag.OPEN -> onRemoteOpen(msg.id)
            MuxFrame.Flag.CLOSE -> onRemoteDisconnect(msg.id)
            MuxFrame.Flag.RESET -> onRemoteClose(msg.id)
            MuxFrame.Flag.DATA -> childRead(msg.id, msg.data!!)
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf) {
        val ctx = getChannelHandlerContext()
        data.sliceMaxSize(maxFrameDataLength)
            .map { frameSliceBuf ->
                MuxFrame(child.id, MuxFrame.Flag.DATA, frameSliceBuf)
            }.forEach { muxFrame ->
                ctx.write(muxFrame)
            }
        ctx.flush()
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
}
