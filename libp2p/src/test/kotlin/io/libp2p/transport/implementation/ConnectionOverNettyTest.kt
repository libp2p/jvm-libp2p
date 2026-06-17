package io.libp2p.transport.implementation

import io.libp2p.core.multiformats.Multiaddr
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConnectionOverNettyTest {

    private val localMultiaddr = Multiaddr("/ip4/127.0.0.1/udp/1234/quic-v1")
    private val remoteMultiaddr = Multiaddr("/ip4/127.0.0.1/udp/5678/quic-v1")

    private fun newConnection(transport: NettyTransport): ConnectionOverNetty =
        ConnectionOverNetty(EmbeddedChannel(), transport, true)

    @Test
    fun `remoteAddress is cached after first read so it survives a freed channel`() {
        val transport = mockk<NettyTransport>()
        // First read resolves from the live channel; a second resolution would NPE because the
        // underlying (QUIC) native channel has been freed and remoteSocketAddress() returns null.
        every { transport.remoteAddress(any()) } returns remoteMultiaddr andThenThrows
            NullPointerException("channel freed")

        val connection = newConnection(transport)

        // Read once while the channel is "live"
        assertThat(connection.remoteAddress()).isEqualTo(remoteMultiaddr)

        // A teardown-time read (e.g. a disconnect handler) must return the cached value rather
        // than hitting the freed channel again.
        assertThat(connection.remoteAddress()).isEqualTo(remoteMultiaddr)

        verify(exactly = 1) { transport.remoteAddress(any()) }
    }

    @Test
    fun `localAddress is cached after first read so it survives a freed channel`() {
        val transport = mockk<NettyTransport>()
        every { transport.localAddress(any()) } returns localMultiaddr andThenThrows
            NullPointerException("channel freed")

        val connection = newConnection(transport)

        assertThat(connection.localAddress()).isEqualTo(localMultiaddr)
        assertThat(connection.localAddress()).isEqualTo(localMultiaddr)

        verify(exactly = 1) { transport.localAddress(any()) }
    }

    @Test
    fun `cacheAddresses warms the cache so a teardown-time read is the first to hit the live channel`() {
        val transport = mockk<NettyTransport>()
        // The transport may only be queried once per address while the channel is live; any later
        // resolution would NPE because the underlying (QUIC) native channel has been freed.
        every { transport.localAddress(any()) } returns localMultiaddr andThenThrows
            NullPointerException("channel freed")
        every { transport.remoteAddress(any()) } returns remoteMultiaddr andThenThrows
            NullPointerException("channel freed")

        val connection = newConnection(transport)

        // Warm the cache while the channel is live (as the QUIC transport does before exposing it).
        connection.cacheAddresses()

        // A teardown handler being the very first caller of the public getters must still succeed,
        // served entirely from the warmed cache without re-hitting the freed channel.
        assertThat(connection.localAddress()).isEqualTo(localMultiaddr)
        assertThat(connection.remoteAddress()).isEqualTo(remoteMultiaddr)

        verify(exactly = 1) { transport.localAddress(any()) }
        verify(exactly = 1) { transport.remoteAddress(any()) }
    }
}
