package io.libp2p.transport.ws

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.NullConnectionUpgrader
import io.libp2p.transport.ws.WsTransport
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

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

    private val upgrader = NullConnectionUpgrader()
    private val nullConnHandler = object : ConnectionHandler {
        override fun handleConnection(conn: Connection) { }
    }
    private val logger = LogManager.getLogger("test")

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
        ws.close().get(5, SECONDS)

        Assertions.assertThrows(Libp2pException::class.java) {
            ws.listen(
                Multiaddr("/ip4/0.0.0.0/tcp/21000/ws"),
                nullConnHandler
            )
                .get(5, SECONDS)
        }
    } // cannotListenOnClosedTransport

    @Test
    fun cannotDialOnClosedTransport() {
        val ws = WsTransport(upgrader)
        ws.close().get(5, SECONDS)

        Assertions.assertThrows(Libp2pException::class.java) {
            ws.dial(
                Multiaddr("/ip4/127.0.0.1/tcp/21000/ws"),
                nullConnHandler
            )
                .get(5, SECONDS)
        }
    } // cannotDialOnClosedTransport

    @Test
    fun listenThenClose() {
        val ws = WsTransport(upgrader)

        val listening = (0..5).map {
            val bindComplete = ws.listen(
                Multiaddr("/ip4/0.0.0.0/tcp/${21000 + it}/ws"),
                nullConnHandler
            )
            bindComplete.handle { _, u -> logger.info("Bound #$it", u) }
            logger.info("Binding #$it")
            bindComplete
        }
        CompletableFuture.allOf(*listening.toTypedArray()).get(5, SECONDS)
        assertEquals(6, ws.activeListeners.size, "No all listeners active")

        ws.close().get(5, SECONDS)
        assertEquals(0, ws.activeListeners.size, "Not all listeners closed")
    }

    @Test
    fun listenThenUnlisten() {
        val ws = WsTransport(upgrader)

        val listening = (0..5).map {
            val bindComplete = ws.listen(
                Multiaddr("/ip4/0.0.0.0/tcp/${21000 + it}/ws"),
                nullConnHandler
            )
            bindComplete.handle { _, u -> logger.info("Bound #$it", u) }
            logger.info("Binding #$it")
            bindComplete
        }
        CompletableFuture.allOf(*listening.toTypedArray()).get(5, SECONDS)
        assertEquals(6, ws.activeListeners.size, "No all listeners active")

        val unlistening = (0..5).map {
            val unbindComplete = ws.unlisten(
                Multiaddr("/ip4/0.0.0.0/tcp/${21000 + it}/ws")
            )
            unbindComplete.handle { _, u -> logger.info("Unbound #$it", u) }
            logger.info("Unbinding #$it")
            unbindComplete
        }
        CompletableFuture.allOf(*unlistening.toTypedArray()).get(5, SECONDS)

        assertEquals(0, ws.activeListeners.size, "Not all listeners closed")
    }

    @Test
    fun dialThenClose() {
        val address = Multiaddr("/ip4/127.0.0.1/tcp/21100/ws")

        val wsServer = WsTransport(upgrader)
        var serverConnections = 0
        val connHandler: ConnectionHandler = object : ConnectionHandler {
            override fun handleConnection(conn: Connection) {
                logger.info("Inbound connection: $conn")
                ++serverConnections
            }
        }

        wsServer.listen(address, connHandler).get(5, SECONDS)
        logger.info("Server is listening")

        val wsClient = WsTransport(upgrader)

        val dialFutures = (1..50).map {
            logger.info("Connecting #$it")
            val dialer = wsClient.dial(address, nullConnHandler)
            dialer.whenComplete { t, u -> logger.info("Connected #$it: $t ($u)") }
        }
        logger.info("Active channels: ${wsClient.activeChannels.size}")

        CompletableFuture.allOf(*dialFutures.toTypedArray()).get(20, SECONDS)
        logger.info("The negotiations succeeded. Closing now...")

        assertEquals(50, wsClient.activeChannels.size)

        wsClient.close().get(5, SECONDS)
        logger.info("Client transport closed")

        wsServer.close().get(5, SECONDS)
        logger.info("Server transport closed")

        // checking that all dial futures are complete (successfully or not)
        val dialCompletions = dialFutures.map { it.handle { t, u -> t to u } }
        CompletableFuture.allOf(*dialCompletions.toTypedArray()).get(5, SECONDS)

        assertEquals(0, wsClient.activeChannels.size)
    }
} // class WsTransportTest
