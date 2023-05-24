package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.mux.mplex.MplexFlag
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.channel.ChannelHandlerContext

class YamuxHandlerTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : YamuxHandler(
            MultistreamProtocolV1, maxFrameDataLength, null, streamHandler, true
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) {
        val muxId = MuxId(parentChannelId, frame.streamId, true)
        val yamuxFrame = when(frame.flag) {
            Open -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.SYN, 0)
            Data -> YamuxFrame(
                muxId,
                YamuxType.DATA,
                0,
                frame.data.fromHex().size.toLong(),
                frame.data.fromHex().toByteBuf(allocateBuf())
            )
            Close -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.FIN, 0)
            Reset -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.RST, 0)
        }
        ech.writeInbound(yamuxFrame)
    }

    override fun readFrame(): AbstractTestMuxFrame? {
        val maybeYamuxFrame = ech.readOutbound<YamuxFrame>()
        return maybeYamuxFrame?.let { yamuxFrame ->
            val flag = when {
                yamuxFrame.flags == YamuxFlags.SYN -> Open
                yamuxFrame.flags == YamuxFlags.FIN -> Close
                yamuxFrame.flags == YamuxFlags.RST -> Reset
                yamuxFrame.type == YamuxType.DATA -> Data
                else -> throw AssertionError("Unsupported yamux frame: $yamuxFrame")
            }
            val sData = yamuxFrame.data?.readAllBytesAndRelease()?.toHex() ?: ""
            AbstractTestMuxFrame(yamuxFrame.id.id, flag, sData)
        }
    }
}
