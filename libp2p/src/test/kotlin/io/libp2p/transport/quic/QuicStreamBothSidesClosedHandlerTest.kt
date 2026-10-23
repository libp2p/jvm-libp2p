package io.libp2p.transport.quic

import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QuicStreamBothSidesClosedHandlerTest {

    private class CapturingHandler : ChannelInboundHandlerAdapter() {
        val events = mutableListOf<Any>()
        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            events.add(evt)
        }
    }

    private val handler = QuicStreamBothSidesClosedHandler()
    private val capturing = CapturingHandler()
    private val channel = EmbeddedChannel(handler, capturing)

    @Test
    fun `stays open while only the remote side finished writing`() {
        channel.pipeline().fireUserEventTriggered(RemoteWriteClosed)

        assertThat(channel.isOpen).isTrue()
    }

    @Test
    fun `stays open while only the local side finished writing`() {
        handler.onLocalWriteClosed()

        assertThat(channel.isOpen).isTrue()
    }

    @Test
    fun `closes the stream once both sides finished writing, remote first`() {
        channel.pipeline().fireUserEventTriggered(RemoteWriteClosed)
        handler.onLocalWriteClosed()

        assertThat(channel.isOpen).isFalse()
    }

    @Test
    fun `closes the stream once both sides finished writing, local first`() {
        // This is the case Netty never closes by itself: after the peer sent STOP_SENDING our FIN
        // is refused, so even a remote FIN arriving later leaves the channel open.
        handler.onLocalWriteClosed()
        channel.pipeline().fireUserEventTriggered(RemoteWriteClosed)

        assertThat(channel.isOpen).isFalse()
    }

    @Test
    fun `still forwards the RemoteWriteClosed event to application handlers`() {
        channel.pipeline().fireUserEventTriggered(RemoteWriteClosed)

        assertThat(capturing.events).containsExactly(RemoteWriteClosed)
    }
}
