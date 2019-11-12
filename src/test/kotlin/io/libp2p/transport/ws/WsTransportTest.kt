package io.libp2p.transport.ws

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.NullConnectionUpgrader
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

@Tag("transport")
class WsTransportTest {
    companion object {
        val nullConnHandler = object : ConnectionHandler {
            override fun handleConnection(conn: Connection) { }
        }
        val logger = LogManager.getLogger("test")

        fun startListeners(server: WsTransport, startPortNumber: Int, howMany: Int) {
            val listening = (1..howMany).map {
                val bindComplete = server.listen(
                    localAddress(startPortNumber + it),
                    nullConnHandler
                )
                bindComplete.handle { _, u -> logger.info("Bound #$it", u) }
                logger.info("Binding #$it")
                bindComplete
            }
            CompletableFuture.allOf(*listening.toTypedArray()).get(5, SECONDS)
            assertEquals(howMany, server.activeListeners, "Not all listeners active")
        }

        fun dialConnections(client: WsTransport, addr: Multiaddr, howMany: Int) {
            val dialFutures = (1..howMany).map {
                logger.info("Connecting #$it")
                val dialer = client.dial(addr, nullConnHandler)
                dialer.whenComplete { t, u -> logger.info("Connected #$it: $t ($u)") }
            }
            CompletableFuture.allOf(*dialFutures.toTypedArray()).get(20, SECONDS)
            logger.info("The negotiations succeeded.")
        }

        fun connectClientAndServer(wsServer: WsTransport, connectionCount: Int): WsTransport {
            val address = localAddress(21100)

            val inboundConnections = object : ConnectionHandler {
                var count = 0
                override fun handleConnection(conn: Connection) {
                    logger.info("Inbound connection $conn")
                    ++count
                }
            }

            wsServer.listen(address, inboundConnections).get()
            logger.info("Server is listening")

            val wsClient = makeTransport()
            dialConnections(wsClient, address, connectionCount)
            assertEquals(connectionCount, wsClient.activeConnections)

            SECONDS.sleep(5) // let things settle
            assertEquals(connectionCount, inboundConnections.count, "Connections not acknowledged by server")
            assertEquals(connectionCount, wsServer.activeConnections)

            return wsClient
        }

        fun makeTransport(): WsTransport {
            return WsTransport(NullConnectionUpgrader())
        } // makeTransport

        fun localAddress(portNumber: Int = 20000): Multiaddr {
            return Multiaddr("/ip4/0.0.0.0/tcp/$portNumber/ws")
        }

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
    } // companion object

    private lateinit var ws: WsTransport

    @BeforeEach
    fun `set up transport`() {
        ws = WsTransport(NullConnectionUpgrader())
    }

    @AfterEach
    fun `close transport`() {
        // transport closed in test, but if we get a failure
        // and assuming close does the right thing!
        ws.close().get(5, SECONDS)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validMultiaddrs")
    fun `WsTransport supports`(addr: Multiaddr) {
        assert(ws.handles(addr))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMultiaddrs")
    fun `WsTransport does not support`(addr: Multiaddr) {
        assert(!ws.handles(addr))
    }

    @Test
    fun `cannot listen on closed transport`() {
        ws.close()

        assertThrows(Libp2pException::class.java) {
            ws.listen(
                localAddress(),
                nullConnHandler
            )
        }
    } // cannotListenOnClosedTransport

    @Test
    fun `cannot dial from a closed transport`() {
        ws.close()

        assertThrows(Libp2pException::class.java) {
            ws.dial(
                localAddress(21000),
                nullConnHandler
            )
        }
    } // cannotDialOnClosedTransport

    @Test
    fun `listen then close transport`() {
        val portNumber = 21000
        val listenerCount = 5
        startListeners(ws, portNumber, listenerCount)

        ws.close().get(5, SECONDS)
        assertEquals(0, ws.activeListeners, "Not all listeners closed")
    }

    @Test
    fun `listen then unlisten`() {
        val portNumber = 21000
        val listenerCount = 5
        startListeners(ws, portNumber, listenerCount)

        val unlistening = (1..listenerCount).map {
            val unbindComplete = ws.unlisten(
                localAddress(portNumber + it)
            )
            unbindComplete.handle { _, u -> logger.info("Unbound #$it", u) }
            logger.info("Unbinding #$it")
            unbindComplete
        }
        CompletableFuture.allOf(*unlistening.toTypedArray()).get(5, SECONDS)

        assertEquals(0, ws.activeListeners, "Not all listeners closed")
    }

    @Test
    fun `dial then close client transport`() {
        val wsClient = connectClientAndServer(ws, 50)

        logger.info("Closing now")
        wsClient.close().get(5, SECONDS)
        logger.info("Client transport closed")
        ws.close().get(5, SECONDS)
        logger.info("Server transport closed")

        assertEquals(0, wsClient.activeConnections, "Not all client connections closed")
    }

    @Test
    fun `dial then close server transport`() {
        val wsClient = connectClientAndServer(ws, 50)

        logger.info("Closing now")
        ws.close().get(5, SECONDS)
        logger.info("Server transport closed")

        SECONDS.sleep(2) // let things settle
        assertEquals(0, wsClient.activeConnections, "Not all client connections closed")

        wsClient.close().get(5, SECONDS)
        logger.info("Client transport closed")
    }
} // class WsTransportTest
