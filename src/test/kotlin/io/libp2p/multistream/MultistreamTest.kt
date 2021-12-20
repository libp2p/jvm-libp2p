package io.libp2p.multistream

import io.libp2p.etc.types.writeUvarint
import io.libp2p.multistream.Negotiator.MAX_MULTISTREAM_MESSAGE_LENGTH
import io.libp2p.tools.Echo
import io.libp2p.tools.TestStreamChannel
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class MultistreamTest {

    @Test
    fun testShouldCloseConnectionOnLongMessage() {
        val channel1 = TestStreamChannel(
            false,
            Echo(),
            LoggingHandler("1", LogLevel.ERROR)
        )

        val buf = Unpooled.buffer().writeUvarint(MAX_MULTISTREAM_MESSAGE_LENGTH + 1)
        channel1.writeInbound(buf)

        Assertions.assertFalse(channel1.isOpen)
    }

    @Test
    fun testZeroRoundtripNegotiation() {
        val channel1 = TestStreamChannel(
            true,
            Echo(),
            LoggingHandler("1", LogLevel.ERROR)
        )

        val channel2 = TestStreamChannel(
            false,
            Echo(),
            LoggingHandler("2", LogLevel.ERROR)
        )

        val initiatorMessages = mutableListOf<ByteBuf>()

        while (true) {
            val buf = channel1.readOutbound<ByteBuf>() ?: break
            initiatorMessages += buf.retainedSlice()
            channel2.writeInbound(buf)
        }

        while (true) {
            val buf = channel2.readOutbound<ByteBuf>() ?: break
            channel1.writeInbound(buf)
        }

        val echoCtrl1 = channel1.controllerFuture.get()
        val echoResp = echoCtrl1.echo("Hello!")

        while (true) {
            val buf = channel1.readOutbound<ByteBuf>() ?: break
            initiatorMessages += buf.retainedSlice()
            channel2.writeInbound(buf)
        }

        while (true) {
            val buf = channel2.readOutbound<ByteBuf>() ?: break
            channel1.writeInbound(buf)
        }

        Assertions.assertEquals("Hello!", echoResp.get())

        channel1.close()
        channel2.close()

        val channel3 = TestStreamChannel(
            false,
            Echo(),
            LoggingHandler("2", LogLevel.ERROR)
        )
        // write all 1 -> 2 messages stick together like below:
        //   /multistream/1.0.0
        //   /test/echo
        //   Hello!
        channel3.writeInbound(Unpooled.wrappedBuffer(*initiatorMessages.toTypedArray()))
        val allOutbound = Unpooled.wrappedBuffer(*channel3.outboundMessages().map { it as ByteBuf }.toTypedArray())
        Assertions.assertEquals(
            "Hello!",
            allOutbound.slice(allOutbound.readableBytes() - 6, 6).toString(StandardCharsets.UTF_8)

        )
    }
}
