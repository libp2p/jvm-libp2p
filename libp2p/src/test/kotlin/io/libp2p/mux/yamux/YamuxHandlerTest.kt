package io.libp2p.mux.yamux

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import io.libp2p.mux.MuxHandler
import io.libp2p.mux.MuxHandlerAbstractTest
import io.libp2p.mux.MuxHandlerAbstractTest.AbstractTestMuxFrame.Flag.*
import io.libp2p.tools.readAllBytesAndRelease
import io.netty.channel.ChannelHandlerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class YamuxHandlerTest : MuxHandlerAbstractTest() {

    override val maxFrameDataLength = 256
    private val readFrameQueue = ArrayDeque<AbstractTestMuxFrame>()

    override fun createMuxHandler(streamHandler: StreamHandler<*>): MuxHandler =
        object : YamuxHandler(
            MultistreamProtocolV1,
            maxFrameDataLength,
            null,
            streamHandler,
            true
        ) {
            // MuxHandler consumes the exception. Override this behaviour for testing
            @Deprecated("Deprecated in Java")
            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                ctx.fireExceptionCaught(cause)
            }
        }

    override fun writeFrame(frame: AbstractTestMuxFrame) {
        val muxId = frame.streamId.toMuxId()
        val yamuxFrame = when (frame.flag) {
            Open -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.SYN, 0)
            Data -> {
                val data = frame.data.fromHex()
                YamuxFrame(
                    muxId,
                    YamuxType.DATA,
                    0,
                    data.size.toLong(),
                    data.toByteBuf(allocateBuf())
                )
            }

            Close -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.FIN, 0)
            Reset -> YamuxFrame(muxId, YamuxType.DATA, YamuxFlags.RST, 0)
        }
        ech.writeInbound(yamuxFrame)
    }

    override fun readFrame(): AbstractTestMuxFrame? {
        val yamuxFrame = readYamuxFrame()
        if (yamuxFrame != null) {
            when (yamuxFrame.flags) {
                YamuxFlags.SYN -> readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Open)
            }

            val data = yamuxFrame.data?.readAllBytesAndRelease()?.toHex() ?: ""
            when {
                yamuxFrame.type == YamuxType.DATA && data.isNotEmpty() ->
                    readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Data, data)
            }

            when (yamuxFrame.flags) {
                YamuxFlags.FIN -> readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Close)
                YamuxFlags.RST -> readFrameQueue += AbstractTestMuxFrame(yamuxFrame.id.id, Reset)
            }
        }

        return readFrameQueue.removeFirstOrNull()
    }

    private fun readYamuxFrame(): YamuxFrame? {
        return ech.readOutbound()
    }

    private fun readYamuxFrameOrThrow() = readYamuxFrame() ?: throw AssertionError("No outbound frames")

    @Test
    fun `test ack new stream`() {
        // signal opening of new stream
        openStream(12)

        writeStream(12, "23")

        val ackFrame = readYamuxFrameOrThrow()

        // receives ack stream
        assertThat(ackFrame.flags).isEqualTo(YamuxFlags.ACK)
        assertThat(ackFrame.type).isEqualTo(YamuxType.WINDOW_UPDATE)

        closeStream(12)
    }

    @Test
    fun `test window update`() {
        openStream(12)

        val largeMessage = "42".repeat(INITIAL_WINDOW_SIZE + 1)
        writeStream(12, largeMessage)

        // ignore ack stream frame
        readYamuxFrameOrThrow()

        val windowUpdateFrame = readYamuxFrameOrThrow()

        assertThat(windowUpdateFrame.flags).isZero()
        assertThat(windowUpdateFrame.type).isEqualTo(YamuxType.WINDOW_UPDATE)
        assertThat(windowUpdateFrame.length).isEqualTo((INITIAL_WINDOW_SIZE + 1).toLong())

        assertLastMessage(0, 1, largeMessage)

        closeStream(12)
    }

    @Test
    fun `data should be buffered and sent after window increased from zero`() {
        val handler = openStreamByLocal()
        val streamId = readFrameOrThrow().streamId

        ech.writeInbound(
            YamuxFrame(
                streamId.toMuxId(),
                YamuxType.WINDOW_UPDATE,
                YamuxFlags.ACK,
                -INITIAL_WINDOW_SIZE.toLong()
            )
        )

        handler.ctx.writeAndFlush("1984".fromHex().toByteBuf(allocateBuf()))

        assertThat(readFrame()).isNull()

        ech.writeInbound(YamuxFrame(streamId.toMuxId(), YamuxType.WINDOW_UPDATE, YamuxFlags.ACK, 5000))
        val frame = readFrameOrThrow()
        assertThat(frame.data).isEqualTo("1984")
    }

    @Test
    fun `buffered data should be partially sent if it does not fit within window`() {
        val handler = openStreamByLocal()
        val streamId = readFrameOrThrow().streamId

        ech.writeInbound(
            YamuxFrame(
                streamId.toMuxId(),
                YamuxType.WINDOW_UPDATE,
                YamuxFlags.ACK,
                -INITIAL_WINDOW_SIZE.toLong()
            )
        )

        val message = "1984".fromHex().toByteBuf(allocateBuf())
        // 2 bytes per message
        handler.ctx.writeAndFlush(message)
        handler.ctx.writeAndFlush(message.copy())

        assertThat(readFrame()).isNull()

        ech.writeInbound(
            YamuxFrame(
                streamId.toMuxId(),
                YamuxType.WINDOW_UPDATE,
                YamuxFlags.ACK,
                3
            )
        )

        var frame = readFrameOrThrow()
        // one message is fully received
        assertThat(frame.data).isEqualTo("1984")
        frame = readFrameOrThrow()
        // the other message is partially received
        assertThat(frame.data).isEqualTo("19")
    }

    @Test
    fun `test ping`() {
        val id: Long = 0
        openStream(id)
        ech.writeInbound(
            YamuxFrame(
                id.toMuxId(),
                YamuxType.PING,
                YamuxFlags.SYN,
                // opaque value, echoed back
                3
            )
        )

        // ignore ack stream frame
        readYamuxFrameOrThrow()

        val pingFrame = readYamuxFrameOrThrow()

        assertThat(pingFrame.flags).isEqualTo(YamuxFlags.ACK)
        assertThat(pingFrame.type).isEqualTo(YamuxType.PING)
        assertThat(pingFrame.length).isEqualTo(3)

        closeStream(id)
    }

    @Test
    fun `test go away`() {
        val id: Long = 0
        openStream(id)
        ech.writeInbound(
            YamuxFrame(
                id.toMuxId(),
                YamuxType.GO_AWAY,
                0,
                // normal termination
                0x0
            )
        )

        // verify session termination
        assertThat(childHandlers[0].isHandlerRemoved).isTrue()
        assertThat(childHandlers[0].isUnregistered).isTrue()
    }
}
