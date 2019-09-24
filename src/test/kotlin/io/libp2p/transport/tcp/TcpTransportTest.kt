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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS

@DisplayName("TcpTransport Tests")
class TcpTransportTest {
    companion object {
        val logger = LogManager.getLogger("test")
        val noOpUpgrader = ConnectionUpgrader(emptyList(), emptyList())

        fun <T> waitOn(f : CompletableFuture<T>) = f.get(5, SECONDS)

        fun localAddress(index: Int) =
            Multiaddr("/ip4/0.0.0.0/tcp/${20000 + index}")

        fun bindListeners(
            tcpTransport: TcpTransport,
            count: Int = 1,
            connectionHandler: ConnectionHandler = ConnectionHandler.create { }
        ) : CompletableFuture<Void> {
            return transportActions(
                { addr: Multiaddr -> tcpTransport.listen(addr, connectionHandler) },
                count,
                "Binding",
                "Bound"
            )
        }

        fun unbindListeners(tcpTransport: TcpTransport, count: Int = 1) : CompletableFuture<Void> {
            return transportActions(
                { addr: Multiaddr -> tcpTransport.unlisten(addr) },
                count,
                "Unbinding",
                "Unbound"
            )
        }

        fun dial(tcpTransport: TcpTransport, count: Int = 1) : CompletableFuture<Void> {
            val connectionHandler = ConnectionHandler.create { }
            return transportActions(
                { addr: Multiaddr -> tcpTransport.dial(addr, connectionHandler) },
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
        ) : CompletableFuture<Void>
        {
            val results = mutableListOf<CompletableFuture<T>>()
            for (i in 1..count) {
                val actionFuture = action(localAddress(i))
                actionFuture.handle { _, u -> logger.info("$completeLabel #$i", u) }
                results += actionFuture
                logger.info("$startedLabel #$i")
            }
            return CompletableFuture.allOf(*results.toTypedArray())
        }
    }

    @Nested
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
    }

    @Nested
    @DisplayName("TcpTransport listen tests")
    class ListenTests {
        @Test
        fun `listeners can be bound and unbound`() {
            val listeners = 50
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(
                bindListeners(tcpTransport, listeners)
            )
            assertEquals(listeners, tcpTransport.activeListeners.size)

            waitOn(
                unbindListeners(tcpTransport, listeners)
            )
            assertEquals(0, tcpTransport.activeListeners.size)
        }

        @Test
        fun `unbind listeners on transport close`() {
            val listeners = 50
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(
                bindListeners(tcpTransport, listeners)
            )
            assertEquals(listeners, tcpTransport.activeListeners.size)

            waitOn(
                tcpTransport.close()
            )
            assertEquals(0, tcpTransport.activeListeners.size)
        }

        @Test
        fun `can not listen on closed transport`() {
            val tcpTransport = TcpTransport(noOpUpgrader)

            waitOn(tcpTransport.close())

            assertThrows(Libp2pException::class.java) {
                bindListeners(tcpTransport)

                // shouldn't reach this, but clean ups
                // in the event the listen doesn't throw
                unbindListeners(tcpTransport)
            }
        }
    }

    @Nested
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
        @Disabled
        fun testDialClose() {
            val (privKey1, pubKey1) = generateKeyPair(KEY_TYPE.ECDSA)
            val upgrader = ConnectionUpgrader(
                listOf(SecIoSecureChannel(privKey1)),
                listOf(MplexStreamMuxer())
            )

            val tcpTransportServer = TcpTransport(upgrader)
            val serverConnections = mutableListOf<Connection>()
            val connHandler: ConnectionHandler = object : ConnectionHandler {
                override fun handleConnection(conn: Connection) {
                    logger.info("Inbound connection: $conn")
                    serverConnections += conn
                }
            }

            tcpTransportServer.listen(
                Multiaddr("/ip4/0.0.0.0/tcp/20000"),
                connHandler
            ).get(5, SECONDS)
            logger.info("Server is listening")

            val tcpTransportClient = TcpTransport(upgrader)

            val dialFutures = mutableListOf<CompletableFuture<Connection>>()
            for (i in 0..50) {
                logger.info("Connecting #$i")
                dialFutures +=
                    tcpTransportClient.dial(Multiaddr("/ip4/127.0.0.1/tcp/20000"), ConnectionHandler.create { })
                dialFutures.last().whenComplete { t, u -> logger.info("Connected #$i: $t ($u)") }
            }
            logger.info("Active channels: ${tcpTransportClient.activeChannels.size}")

            CompletableFuture.anyOf(*dialFutures.toTypedArray()).get(5, SECONDS)
            logger.info("The first negotiation succeeded. Closing now...")

            tcpTransportClient.close().get(5, SECONDS)
            logger.info("Client transport closed")
            tcpTransportServer.close().get(5, SECONDS)
            logger.info("Server transport closed")

            // checking that all dial futures are complete (successfully or not)
            val dialCompletions = dialFutures.map { it.handle { t, u -> t to u } }
            CompletableFuture.allOf(*dialCompletions.toTypedArray()).get(5, SECONDS)
        }
    }
}
