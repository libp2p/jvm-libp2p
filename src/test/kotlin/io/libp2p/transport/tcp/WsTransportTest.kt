package io.libp2p.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.ws.WsTransport
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit

@Tag("transport")
class WsTransportTest {
    companion object {
        @JvmStatic
        fun validMultiaddrs() = listOf(
            "/ip4/1.2.3.4/tcp/1234/ws",
            "/ip4/0.0.0.0/tcp/1234/ws",
            "/ip4/1.2.3.4/tcp/0/ws",
            "/ip4/0.0.0.0/tcp/1234/ws",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42/ws"
        ).map { Multiaddr(it) }

        @JvmStatic
        fun invalidMultiaddrs() = listOf(
            "/ip4/1.2.3.4/tcp/1234",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip4/1.2.3.4/tcp/0",
            "/ip4/0.0.0.0/tcp/1234",
            "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42",
            "/ip4/1.2.3.4/udp/42",
            "/unix/a/file/named/tcp"
        ).map { Multiaddr(it) }
    }

    private val upgrader = ConnectionUpgrader(emptyList(), emptyList())
    private val nullConnHandler = object : ConnectionHandler {
        override fun handleConnection(conn: Connection) { }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validMultiaddrs")
    fun `WsTransport supports`(addr: Multiaddr) {
        val ws = WsTransport(upgrader)
        assert(ws.handles(addr))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMultiaddrs")
    fun `WsTransport does not support`(addr: Multiaddr) {
        val ws = WsTransport(upgrader)
        assert(!ws.handles(addr))
    }

    @Test
    fun cannotListenOnClosedTransport() {
        val ws = WsTransport(upgrader)
        ws.close().get(5, TimeUnit.SECONDS)

        Assertions.assertThrows(Libp2pException::class.java) {
            ws.listen(
                Multiaddr("/ip4/0.0.0.0/tcp/20000/ws"),
                nullConnHandler
            )
                .get(5, TimeUnit.SECONDS)
        }
    } // cannotListenOnClosedTransport

    @Test
    fun cannotDialOnClosedTransport() {
        val ws = WsTransport(upgrader)
        ws.close().get(5, TimeUnit.SECONDS)

        Assertions.assertThrows(Libp2pException::class.java) {
            ws.dial(
                Multiaddr("/ip4/127.0.0.1/tcp/20000/ws"),
                nullConnHandler
            )
                .get(5, TimeUnit.SECONDS)
        }
    } // cannotDialOnClosedTransport
} // class WsTransportTest
