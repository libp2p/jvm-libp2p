package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.etc.util.netty.mux.MuxChannel
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
        MplexId(getChannelHandlerContext().channel().id(), idGenerator.incrementAndGet(), true)

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as MplexFrame
        when (msg.flag.type) {
            MplexFlag.Type.OPEN -> {
                onRemoteOpen(msg.id)
                msg.release()
            }
            MplexFlag.Type.CLOSE -> {
                onRemoteDisconnect(msg.id)
                msg.release()
            }
            MplexFlag.Type.RESET -> {
                onRemoteClose(msg.id)
                msg.release()
            }
            MplexFlag.Type.DATA -> childRead(msg.id, msg.data)
        }
    }

    override fun onChildWrite(child: MuxChannel<ByteBuf>, data: ByteBuf): Int {
        val ctx = getChannelHandlerContext()
        var written = 0
        while (written < data.readableBytes()) {
            val frameLength = minOf(
                data.readableBytes() - written,
                maxFrameDataLength
            )
            val frameSliceBuf = data.retainedSlice(data.readerIndex() + written, frameLength)
            ctx.write(MplexFrame.createDataFrame(child.id, frameSliceBuf))
            written += frameLength
        }
        return written
    }

    override fun onLocalOpen(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createOpenFrame(child.id))
    }

    override fun onLocalDisconnect(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createCloseFrame(child.id))
    }

    override fun onLocalClose(child: MuxChannel<ByteBuf>) {
        getChannelHandlerContext().writeAndFlush(MplexFrame.createResetFrame(child.id))
    }

    override fun onChildClosed(child: MuxChannel<ByteBuf>) {}
}
