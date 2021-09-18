package io.libp2p.transport.ws

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.tools.DnsAvailability.Companion.ip4DnsAvailable
import io.libp2p.transport.NullConnectionUpgrader
import io.libp2p.transport.TransportTests
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@Tag("ws-transport")
class WsTransportTest : TransportTests() {
    override fun makeTransport(): WsTransport {
        return WsTransport(NullConnectionUpgrader())
    } // makeTransport

    override fun localAddress(portNumber: Int): Multiaddr {
        return if (ip4DnsAvailable && (portNumber % 2 == 0))
            Multiaddr("/dns4/localhost/tcp/$portNumber/ws")
        else
            Multiaddr("/ip4/127.0.0.1/tcp/$portNumber/ws")
    } // localAddress

    override fun badAddress(): Multiaddr =
        Multiaddr("/dns4/host.invalid/tcp/4000/ws")

    companion object {
        @JvmStatic
        fun validMultiaddrs() = listOf(
            "/ip4/1.2.3.4/tcp/1234/ws",
            "/ip4/0.0.0.0/tcp/1234/ws",
            "/ip4/1.2.3.4/tcp/0/ws",
            "/ip4/0.0.0.0/tcp/1234/ws",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42/ws",
            "/dns4/localhost/tcp/9999/ws",
            "/dns6/localhost/tcp/9999/ws"
        ).map { Multiaddr(it) }

        @JvmStatic
        fun invalidMultiaddrs() = listOf(
            "/ip4/1.2.3.4/tcp/1234",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip4/1.2.3.4/tcp/0",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42",
            "/ip4/1.2.3.4/udp/42",
            "/unix/a/file/named/tcp",
            "/dns4/localhost/udp/9999/ws",
            "/dns6/localhost/udp/9999/ws",
            "/dns4/localhost/udp/9999",
            "/dns6/localhost/udp/9999"
        ).map { Multiaddr(it) }
    } // companion object

    @ParameterizedTest(name = "{0}")
    @MethodSource("validMultiaddrs")
    fun `WsTransport supports`(addr: Multiaddr) {
        assert(transportUnderTest.handles(addr))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMultiaddrs")
    fun `WsTransport does not support`(addr: Multiaddr) {
        assert(!transportUnderTest.handles(addr))
    }
} // class WsTransportTest
