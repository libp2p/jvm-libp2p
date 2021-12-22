package io.libp2p.mux.mplex

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.DefaultChannelId
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.charset.StandardCharsets.UTF_8

class MplexFrameCodecTest {

    companion object {
        @JvmStatic
        fun splitIndexes() = listOf(
            listOf(),
            listOf(20),
            listOf(10),
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
            listOf(2, 4, 8)
        )
    }
    val dummyId = DefaultChannelId.newInstance()

    @Test
    fun `check max frame size limit`() {
        val channelLarge = EmbeddedChannel(MplexFrameCodec(maxFrameDataLength = 1024))

        val mplexFrame = MplexFrame(
            MuxId(dummyId, 777, true), MplexFlags.MessageInitiator,
            ByteArray(1024).toByteBuf()
        )

        assertTrue(
            channelLarge.writeOutbound(mplexFrame)
        )
        val largeFrameBytes = channelLarge.readOutbound<ByteBuf>()
        val largeFrameBytesTrunc = largeFrameBytes.slice(0, largeFrameBytes.readableBytes() - 1)

        val channelSmall = EmbeddedChannel(MplexFrameCodec(maxFrameDataLength = 128))

        assertThrows<DecoderException> {
            channelSmall.writeInbound(largeFrameBytesTrunc)
        }

        assertFalse(channelSmall.isOpen)
    }

    @ParameterizedTest
    @MethodSource("splitIndexes")
    fun testDecoder(sliceIdx: List<Int>) {
        val channel = EmbeddedChannel(MplexFrameCodec())

        val mplexFrames = arrayOf(
            MplexFrame(MuxId(dummyId, 777, true), MplexFlags.MessageInitiator, "Hello-1".toByteArray().toByteBuf()),
            MplexFrame(MuxId(dummyId, 888, true), MplexFlags.MessageInitiator, "Hello-2".toByteArray().toByteBuf()),
            MplexFrame(MuxId(dummyId, 999, true), MplexFlags.MessageInitiator, "Hello-3".toByteArray().toByteBuf())
        )
        assertTrue(
            channel.writeOutbound(*mplexFrames)
        )

        val rawData = Unpooled.wrappedBuffer(
            channel.readOutbound<ByteBuf>(),
            channel.readOutbound<ByteBuf>(),
            channel.readOutbound<ByteBuf>()
        )

        for (i in 0..sliceIdx.size) {
            val startIdx = if (i == 0) 0 else sliceIdx[i - 1]
            val endIdx = if (i == sliceIdx.size) rawData.writerIndex() else sliceIdx[i]
            channel.writeInbound(rawData.retainedSlice(startIdx, endIdx - startIdx))
        }
        channel.checkException()

        val resultFrames = List(3) { channel.readInbound<MplexFrame>() }
        assertEquals(777, resultFrames[0].id.id)
        assertEquals(888, resultFrames[1].id.id)
        assertEquals(999, resultFrames[2].id.id)
        assertEquals("Hello-1", resultFrames[0].data!!.toByteArray().toString(UTF_8))
        assertEquals("Hello-2", resultFrames[1].data!!.toByteArray().toString(UTF_8))
        assertEquals("Hello-3", resultFrames[2].data!!.toByteArray().toString(UTF_8))
    }
}
