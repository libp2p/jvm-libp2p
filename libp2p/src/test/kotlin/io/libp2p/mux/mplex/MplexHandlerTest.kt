package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MplexHandlerTest : MuxHandlerAbstractTest() {

    private fun writeInboundControlFrame(streamId: Long, type: MplexFlag.Type) {
        // Simulate what MplexFrameCodec.decode() produces: a retained slice of the
        // inbound buffer carried as the MplexFrame data, even for OPEN/CLOSE/RESET.
        val data = allocateBuf()
        val muxId = MplexId(parentChannelId, streamId, true)
        ech.writeInbound(MplexFrame(muxId, MplexFlag.getByType(type, true), data))
    }

    @Test
    fun `OPEN frame data buffer is released`() {
        writeInboundControlFrame(1, MplexFlag.Type.OPEN)
        // cleanUpAndCheck verifies the allocated buffer's refCnt was decremented.
    }

    @Test
    fun `CLOSE frame data buffer is released`() {
        val id = openStreamRemote()
        writeInboundControlFrame(id, MplexFlag.Type.CLOSE)
    }

    @Test
    fun `RESET frame data buffer is released`() {
        val id = openStreamRemote()
        writeInboundControlFrame(id, MplexFlag.Type.RESET)
    }

    @Test
    fun `repeated OPEN frames do not leak buffers`() {
        repeat(50) { i ->
            writeInboundControlFrame(i.toLong(), MplexFlag.Type.OPEN)
        }
        assertThat(allocatedBufs).hasSize(50)
    }

    override val maxFrameDataLength = 256

    override val localMuxIdGenerator = (0L..Long.MAX_VALUE).iterator()
    override val remoteMuxIdGenerator = (0L..Long.MAX_VALUE).iterator()

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : MplexHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            streamHandler
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) {
        val muxId = MplexId(parentChannelId, frame.streamId, true)
        val mplexFlag = when (frame.flag) {
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
            MplexFrame(muxId, MplexFlag.getByType(mplexFlag, true), data)
        ech.writeInbound(mplexFrame)
    }

    override fun readFrame(): AbstractTestMuxFrame? {
        val maybeMplexFrame = ech.readOutbound<MplexFrame>()
        return maybeMplexFrame?.let { mplexFrame ->
            val flag = when (mplexFrame.flag.type) {
                MplexFlag.Type.OPEN -> Open
                MplexFlag.Type.DATA -> Data
                MplexFlag.Type.CLOSE -> Close
                MplexFlag.Type.RESET -> Reset
            }
            val data = maybeMplexFrame.data.readAllBytesAndRelease().toHex()
            AbstractTestMuxFrame(mplexFrame.id.id, flag, data)
        }
    }
}
