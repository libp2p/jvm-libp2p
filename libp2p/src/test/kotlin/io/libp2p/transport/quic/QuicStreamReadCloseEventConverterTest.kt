package io.libp2p.transport.quic

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.ChannelOutputShutdownException
import io.netty.handler.codec.quic.QuicStreamResetException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuicStreamReadCloseEventConverterTest {

    /** Records exceptions that reach application handlers (i.e. were not swallowed by the converter). */
    private class CapturingHandler : ChannelInboundHandlerAdapter() {
        val caught = mutableListOf<Throwable>()
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            caught.add(cause)
        }
    }

    private fun channelWithConverter(): Pair<EmbeddedChannel, CapturingHandler> {
        val capturing = CapturingHandler()
        // Converter first (as QuicStream installs it), application handler after it.
        val channel = EmbeddedChannel(QuicStreamReadCloseEventConverter(), capturing)
        return channel to capturing
    }

    @Test
    fun `swallows STOP_SENDING ChannelOutputShutdownException without surfacing it or closing the stream`() {
        val (channel, capturing) = channelWithConverter()

        channel.pipeline().fireExceptionCaught(ChannelOutputShutdownException("STOP_SENDING frame received"))

        // STOP_SENDING only stops our writes; the read side may still be live, so the stream must
        // stay open and the exception must not reach application handlers.
        assertThat(capturing.caught).isEmpty()
        assertThat(channel.isOpen).isTrue()
    }

    @Test
    fun `closes the stream quietly on a remote QuicStreamResetException`() {
        val (channel, capturing) = channelWithConverter()

        channel.pipeline().fireExceptionCaught(QuicStreamResetException("reset", 0))

        assertThat(capturing.caught).isEmpty()
        assertThat(channel.isOpen).isFalse()
    }

    @Test
    fun `propagates unrelated exceptions to application handlers`() {
        val (channel, capturing) = channelWithConverter()
        val unrelated = RuntimeException("boom")

        channel.pipeline().fireExceptionCaught(unrelated)

        assertThat(capturing.caught).containsExactly(unrelated)
    }
}
