package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.mockk.every
import io.mockk.mockk
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.socket.ChannelOutputShutdownException
import io.netty.handler.codec.quic.QuicStreamChannel
import io.netty.util.concurrent.ImmediateEventExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.channels.ClosedChannelException

class QuicStreamTest {

    private val channel = mockk<QuicStreamChannel>(relaxed = true)
    private val connection = mockk<Connection>(relaxed = true)

    private fun promise() = DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE)

    private fun openStream(): QuicStream {
        every { channel.isOpen } returns true
        every { channel.isOutputShutdown } returns false
        return QuicStream(channel, connection, initiator = false)
    }

    @Test
    fun `closeWrite marks the local side closed when the FIN is sent`() {
        every { channel.shutdownOutput() } returns promise().setSuccess()
        val stream = openStream()

        val future = stream.closeWrite()

        assertThat(future).isCompleted()
        assertThat(stream.bothSidesClosedHandler.localWriteClosed).isTrue()
    }

    @Test
    fun `closeWrite marks the local side closed when the FIN is refused after STOP_SENDING`() {
        // After STOP_SENDING quiche refuses our FIN for good (it has reset our send side instead),
        // so the local side is finished even though no FIN went out; only tracking it here lets the
        // stream be closed once the remote side finishes too, which Netty would never do by itself.
        every { channel.shutdownOutput() } returns
            promise().setFailure(ChannelOutputShutdownException("STOP_SENDING frame received"))
        val stream = openStream()

        val future = stream.closeWrite()

        assertThat(future).isCompleted()
        assertThat(stream.bothSidesClosedHandler.localWriteClosed).isTrue()
    }

    @Test
    fun `closeWrite marks the local side closed when the FIN was already sent`() {
        every { channel.shutdownOutput() } returns
            promise().setFailure(ChannelOutputShutdownException("Fin was sent already"))
        val stream = openStream()

        val future = stream.closeWrite()

        assertThat(future).isCompleted()
        assertThat(stream.bothSidesClosedHandler.localWriteClosed).isTrue()
    }

    @Test
    fun `closeWrite tolerates an already closed stream`() {
        every { channel.shutdownOutput() } returns promise().setFailure(ClosedChannelException())
        val stream = openStream()

        val future = stream.closeWrite()

        assertThat(future).isCompleted()
        assertThat(stream.bothSidesClosedHandler.localWriteClosed).isTrue()
    }

    @Test
    fun `closeWrite marks the local side closed when the output was already shut down`() {
        every { channel.isOpen } returns true
        every { channel.isOutputShutdown } returns true
        val stream = QuicStream(channel, connection, initiator = false)

        val future = stream.closeWrite()

        assertThat(future).isCompleted()
        assertThat(stream.bothSidesClosedHandler.localWriteClosed).isTrue()
    }

    @Test
    fun `closeWrite still fails on an unexpected error`() {
        every { channel.shutdownOutput() } returns promise().setFailure(IllegalStateException("boom"))
        val stream = openStream()

        val future = stream.closeWrite()

        assertThat(future).isCompletedExceptionally()
    }
}
