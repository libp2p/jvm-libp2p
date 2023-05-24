package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.etc.util.netty.mux.MuxId
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext

class MplexHandlerTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : MplexHandler(
            MultistreamProtocolV1, maxFrameDataLength, null, streamHandler
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) {
        val mplexFlag = when(frame.flag) {
            Open -> MplexFlag.Type.OPEN
            Data -> MplexFlag.Type.DATA
            Close -> MplexFlag.Type.CLOSE
            Reset -> MplexFlag.Type.RESET
        }
        val data = when {
            frame.data.isEmpty() -> Unpooled.EMPTY_BUFFER
            else -> frame.data.fromHex().toByteBuf(allocateBuf())
        }
        val mplexFrame =
            MplexFrame(MuxId(parentChannelId, frame.streamId, true), MplexFlag.getByType(mplexFlag, true), data)
        ech.writeInbound(mplexFrame)
    }

    override fun readFrame(): AbstractTestMuxFrame? {
        val maybeMplexFrame = ech.readOutbound<MplexFrame>()
        return maybeMplexFrame?.let { mplexFrame ->
            val flag = when(mplexFrame.flag.type) {
                MplexFlag.Type.OPEN -> Open
                MplexFlag.Type.DATA -> Data
                MplexFlag.Type.CLOSE -> Close
                MplexFlag.Type.RESET -> Reset
                else -> throw AssertionError("Unknown mplex flag: ${mplexFrame.flag}")
            }
            val sData = maybeMplexFrame.data.readAllBytesAndRelease().toHex()
            AbstractTestMuxFrame(mplexFrame.id.id, flag, sData)
        }
    }
}
