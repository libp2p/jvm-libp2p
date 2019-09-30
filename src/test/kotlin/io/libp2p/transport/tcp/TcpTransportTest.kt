package io.libp2p.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.ConnectionUpgrader
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("TcpTransport Tests")
class TcpTransportTest {
    @DisplayName("TcpTransport handles tests")
    class HandlesTests {
        @ParameterizedTest
        @MethodSource("validMultiaddrs")
        fun `handles(addr) succeeds when addr is a tcp protocol`(addr: Multiaddr) {
            val tcp = TcpTransport(noOpUpgrader)
            assertTrue(tcp.handles(addr))
        }

        @ParameterizedTest
        @MethodSource("invalidMultiaddrs")
        fun `handles(addr) fails when addr is not a tcp protocol`(addr: Multiaddr) {
            val tcp = TcpTransport(noOpUpgrader)
            assertFalse(tcp.handles(addr))
        }

        companion object {
            @JvmStatic
            fun validMultiaddrs() = listOf(
                "/ip4/1.2.3.4/tcp/1234",
                "/ip6/fe80::6f77:b303:aa6e:a16/tcp/42"
            ).map { Multiaddr(it) }

            @JvmStatic
            fun invalidMultiaddrs() = listOf(
                "/ip4/1.2.3.4/udp/42",
                "/unix/a/file/named/tcp"
            ).map { Multiaddr(it) }
        }
    } // HandlesTests

    @DisplayName("TcpTransport listen tests")
    class ListenTests {
        @Test
        fun `listeners can be bound and unbound`() {
            val listeners = 50
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(
                bindListeners(tcpTransport, listeners)
            )
            val openedListeners = tcpTransport.activeListeners.size

            waitOn(
                unbindListeners(tcpTransport, listeners)
            )
            assertEquals(listeners, openedListeners)
            assertEquals(0, tcpTransport.activeListeners.size)
        }

        @Test
        fun `unbind listeners on transport close`() {
            val listeners = 50
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(
                bindListeners(tcpTransport, listeners)
            )
            val openedListeners = tcpTransport.activeListeners.size

            waitOn(
                tcpTransport.close()
            )
            assertEquals(listeners, openedListeners)
            assertEquals(0, tcpTransport.activeListeners.size)
        }

        @Test
        fun `can not listen on closed transport`() {
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(tcpTransport.close())

            assertThrows(Libp2pException::class.java) {
                bindListeners(tcpTransport)
            }
        }
    } // ListenTests

    @DisplayName("TcpTransport dial tests")
    class DialTests {
        @Test
        fun `can not dial on a closed transport`() {
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(tcpTransport.close())

            assertThrows(Libp2pException::class.java) {
                dial(tcpTransport)
            }
        }

        @Test
        fun `dialled connections can be closed`() {
            val (tcpListener, handler) = startListener()
            val dialledConnections: DialledConnections

            val connectionsToDial = 10
            val tcpClient = TcpTransport(secMuxUpgrader)
            try {
                dialledConnections = dialConnections(tcpClient, connectionsToDial)

                assertEquals(connectionsToDial, dialledConnections.size)
                if (connectionsToDial != handler.connectionsEstablished)
                    logger.info("${handler.connectionsEstablished} of $connectionsToDial connections established")

                for (channel in tcpClient.activeChannels.toList())
                    channel.close()

                waitOn(
                    dialledConnections.allClosed
                )
                logger.info("All channels closed")
            } finally {
                tcpClient.close()
                tcpListener.close()
            }
        }

        @Test
        fun `disconnect dialed connection on client close`() {
            val (tcpListener, handler) = startListener()
            val dialledConnections: DialledConnections

            val connectionsToDial = 10
            val tcpClient = TcpTransport(secMuxUpgrader)
            try {
                dialledConnections = dialConnections(tcpClient, connectionsToDial)
            } finally {
                waitOn(
                    tcpClient.close()
                )
                logger.info("Client transport closed")
            }

            assertEquals(connectionsToDial, dialledConnections.size)
            if (connectionsToDial != handler.connectionsEstablished)
                logger.info("${handler.connectionsEstablished} of $connectionsToDial connections established")

            waitOn(
                dialledConnections.allClosed
            )

            tcpListener.close()
            logger.info("Server transport closed")
        }

        @Test
        fun `disconnect dialed connection on server close`() {
            val (tcpListener, handler) = startListener()
            val dialledConnections: DialledConnections

            val connectionsToDial = 10
            val tcpClient = TcpTransport(secMuxUpgrader)
            try {
                dialledConnections = dialConnections(tcpClient, connectionsToDial)
            } finally {
                waitOn(
                    tcpListener.close()
                )
                logger.info("Server transport closed")
            }

            assertEquals(connectionsToDial, dialledConnections.size)
            if (connectionsToDial != handler.connectionsEstablished)
                logger.info("${handler.connectionsEstablished} of $connectionsToDial connections established")

            waitOn(
                dialledConnections.allClosed
            )

            tcpClient.close()
            logger.info("Client transport closed")
        }

        data class ListenSetup(val tcpListener: TcpTransport, val handler: CountingConnectionHandler)
        fun startListener(): ListenSetup {
            val handler = CountingConnectionHandler()
            val tcpListener = TcpTransport(secMuxUpgrader)
            waitOn(
                bindListeners(tcpListener, connectionHandler = handler)
            )
            logger.info("Server is listening")

            return ListenSetup(tcpListener, handler)
        }

        data class DialledConnections(val size: Int, val allClosed: CompletableFuture<Void>)
        fun dialConnections(tcpDialler: TcpTransport, connectionsToDial: Int): DialledConnections {
            val connections = dial(tcpDialler, connectionsToDial)
            waitOn(
                CompletableFuture.allOf(*connections.toTypedArray())
            )
            logger.info("Negotiation succeeded.")

            val closeCompletions = connections.map { it.get().closeFuture() }
            return DialledConnections(
                connections.size,
                CompletableFuture.allOf(*closeCompletions.toTypedArray())
            )
        }

        class CountingConnectionHandler : ConnectionHandler {
            private var connectionsCount = AtomicInteger(0)
            val connectionsEstablished: Int get() = connectionsCount.get()

            override fun handleConnection(conn: Connection) {
                val count = connectionsCount.incrementAndGet()
                logger.info("Inbound connection: $count")
            }
        }
    } // DialTests

    companion object {
        val logger = LogManager.getLogger("test")
        val noOpUpgrader = ConnectionUpgrader(
            emptyList(),
            emptyList()
        )
        val secMuxUpgrader = ConnectionUpgrader(
            listOf(SecIoSecureChannel(generateKeyPair(KEY_TYPE.ECDSA).first)),
            listOf(MplexStreamMuxer())
        )

        fun <T> waitOn(f: CompletableFuture<T>) = f.get(5, SECONDS)

        fun localAddress(index: Int) =
            Multiaddr("/ip4/127.0.0.1/tcp/${20000 + index}")

        fun bindListeners(
            tcpTransport: TcpTransport,
            count: Int = 1,
            connectionHandler: ConnectionHandler = ConnectionHandler.create { }
        ): CompletableFuture<Void> {
            return transportActionsAsVoidFuture(
                { addr: Multiaddr -> tcpTransport.listen(addr, connectionHandler) },
                count,
                "Binding",
                "Bound"
            )
        }

        fun unbindListeners(tcpTransport: TcpTransport, count: Int = 1): CompletableFuture<Void> {
            return transportActionsAsVoidFuture(
                { addr: Multiaddr -> tcpTransport.unlisten(addr) },
                count,
                "Unbinding",
                "Unbound"
            )
        }

        fun dial(tcpTransport: TcpTransport, count: Int = 1): List<CompletableFuture<Connection>> {
            val connectionHandler = ConnectionHandler.create { }
            val dialAddress = localAddress(1)
            return transportActions(
                { _ -> tcpTransport.dial(dialAddress, connectionHandler) },
                count,
                "Dialing",
                "Dialed"
            )
        }

        fun <T> transportActions(
            action: (addr: Multiaddr) -> CompletableFuture<T>,
            count: Int,
            startedLabel: String,
            completeLabel: String
        ): List<CompletableFuture<T>> {
            val results = mutableListOf<CompletableFuture<T>>()
            for (i in 1..count) {
                val actionFuture = action(localAddress(i))
                actionFuture.handle { _, u -> logger.info("$completeLabel #$i", u) }
                results += actionFuture
                logger.info("$startedLabel #$i")
            }
            return results
        }

        fun <T> transportActionsAsVoidFuture(
            action: (addr: Multiaddr) -> CompletableFuture<T>,
            count: Int,
            startedLabel: String,
            completeLabel: String
        ): CompletableFuture<Void> {
            val results = transportActions(action, count, startedLabel, completeLabel)
            return CompletableFuture.allOf(*results.toTypedArray())
        }
    } // companion object
}
