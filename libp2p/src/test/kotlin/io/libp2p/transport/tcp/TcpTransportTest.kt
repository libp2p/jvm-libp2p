package io.libp2p.transport.tcp

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol.TCP
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import io.libp2p.tools.DnsAvailability.Companion.ip4DnsAvailable
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.NullConnectionUpgrader
import io.libp2p.transport.NullMultistreamProtocol
import io.libp2p.transport.TransportTests
import io.libp2p.transport.implementation.PlainNettyTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.net.BindException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

@Tag("tcp-transport")
class TcpTransportTest : TransportTests() {
    override fun makeTransport(): Transport {
        return TcpTransport(NullConnectionUpgrader())
    }

    override fun localAddress(portNumber: Int): Multiaddr {
        return if (ip4DnsAvailable && (portNumber % 2 == 0)) {
            Multiaddr("/dns4/localhost/tcp/$portNumber")
        } else {
            Multiaddr("/ip4/127.0.0.1/tcp/$portNumber")
        }
    }

    override fun badAddress(): Multiaddr =
        Multiaddr("/dns4/host.invalid/tcp/4000")

    companion object {
        @JvmStatic
        fun validMultiaddrs() = listOf(
            "/ip4/1.2.3.4/tcp/1234",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip4/1.2.3.4/tcp/0",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42",
            "/dns4/localhost/tcp/9999",
            "/dns6/localhost/tcp/9999"
        ).map { Multiaddr(it) }

        @JvmStatic
        fun invalidMultiaddrs() = listOf(
            "/dnsaddr/ipfs.io/udp/97",
            "/ip4/1.2.3.4/tcp/1234/ws",
            "/ip4/1.2.3.4/udp/42",
            "/dns4/localhost/tcp/1234/ws",
            "/dns4/localhost/udp/9999",
            "/dns6/localhost/udp/9999",
            "/unix/a/file/named/tcp"
        ).map { Multiaddr(it) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validMultiaddrs")
    fun `TcpTransport supports`(addr: Multiaddr) {
        assert(transportUnderTest.handles(addr))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMultiaddrs")
    fun `TcpTransport does not support`(addr: Multiaddr) {
        assert(!transportUnderTest.handles(addr))
    }

    @Test
    fun `close releases ipv6 listener socket`() {
        val loopback = InetAddress.getByName("::1") as Inet6Address
        assumeIpv6LoopbackBindable(loopback)

        transportUnderTest.listen(Multiaddr("/ip6/::1/tcp/0"), nullConnHandler).get(5, SECONDS)
        val boundPort = requireNotNull(
            transportUnderTest.listenAddresses().single().getFirstComponent(TCP)
        ).let { requireNotNull(it.stringValue).toInt() }

        assertThrows(BindException::class.java) {
            bindIpv6Loopback(loopback, boundPort).use { }
        }

        transportUnderTest.close().get(5, SECONDS)

        assertEquals(0, transportUnderTest.activeListeners, "IPv6 listener still tracked after close")
        bindIpv6Loopback(loopback, boundPort).use { }
    }

    @Test
    fun `cancelling pending ipv6 dial closes socket`() {
        val loopback = InetAddress.getByName("::1") as Inet6Address
        assumeIpv6LoopbackBindable(loopback)

        bindIpv6Loopback(loopback, 0).use { server ->
            val serverPort = (server.localAddress as InetSocketAddress).port
            val transport = TcpTransport(PendingConnectionUpgrader())
            try {
                val dial = transport.dial(
                    Multiaddr("/ip6/::1/tcp/$serverPort"),
                    nullConnHandler
                )
                assertActiveConnectionsEventually(transport, 1)

                assertTrue(dial.cancel(true))

                assertActiveConnectionsEventually(transport, 0)
            } finally {
                transport.close().get(5, SECONDS)
            }
        }
    }

    private fun assumeIpv6LoopbackBindable(loopback: Inet6Address) {
        try {
            bindIpv6Loopback(loopback, 0).use { }
        } catch (e: IOException) {
            assumeTrue(false, "IPv6 loopback is not available: ${e.message}")
        }
    }

    private fun bindIpv6Loopback(loopback: Inet6Address, port: Int): ServerSocketChannel =
        ServerSocketChannel.open().apply {
            bind(InetSocketAddress(loopback, port))
        }

    private fun assertActiveConnectionsEventually(transport: PlainNettyTransport, expected: Int) {
        val deadline = System.nanoTime() + SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (transport.activeConnections == expected) {
                return
            }
            Thread.sleep(10)
        }
        assertEquals(expected, transport.activeConnections)
    }

    private class PendingConnectionUpgrader :
        ConnectionUpgrader(NullMultistreamProtocol(), emptyList(), NullMultistreamProtocol(), emptyList()) {
        override fun establishSecureChannel(connection: io.libp2p.core.Connection): CompletableFuture<SecureChannel.Session> =
            CompletableFuture()
    }
}
