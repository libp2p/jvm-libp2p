package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import org.apache.logging.log4j.LogManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean

@Tag("transport")
abstract class TransportTests {
    protected abstract fun makeTransport(): Transport
    protected abstract fun localAddress(portNumber: Int = 20000): Multiaddr
    protected abstract fun badAddress(): Multiaddr
    protected lateinit var transportUnderTest: Transport

    protected val nullConnHandler = ConnectionHandler { }
    protected val logger = LogManager.getLogger("test")

    protected fun startListeners(server: Transport, startPortNumber: Int, howMany: Int) {
        val listening = (0 until howMany).map {
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
    } // startListeners

    protected fun dialConnections(client: Transport, addr: Multiaddr, howMany: Int) {
        val dialFutures = (1..howMany).map {
            logger.info("Connecting #$it")
            val dialer = client.dial(addr, nullConnHandler)
            dialer.whenComplete { t, u -> logger.info("Connected #$it: $t ($u)") }
        }
        CompletableFuture.allOf(*dialFutures.toTypedArray()).get(20, SECONDS)
        logger.info("The negotiations succeeded.")
    } // dialConnections

    protected fun connectClientAndServer(server: Transport, connectionCount: Int): Transport {
        val address = localAddress(21100)

        val inboundConnections = object : ConnectionHandler {
            var count = 0
            override fun handleConnection(conn: Connection) {
                logger.info("Inbound connection $conn")
                ++count
            }
        }

        server.listen(address, inboundConnections).get()
        logger.info("Server is listening")

        val client = makeTransport()
        dialConnections(client, address, connectionCount)
        assertEquals(connectionCount, client.activeConnections)

        // give the server time to acknowledge connections
        for (attempt in 1..30) {
            if (connectionCount == inboundConnections.count) {
                break
            }
            SECONDS.sleep(1)
        }

        assertEquals(connectionCount, inboundConnections.count, "Connections not acknowledged by server")
        assertEquals(connectionCount, server.activeConnections)

        return client
    } // connectClientAndServer

    @BeforeEach
    fun `set up transport`() {
        transportUnderTest = makeTransport()
    }

    @AfterEach
    fun `close transport`() {
        // transport closed in test, but if we get a failure
        // and assuming close does the right thing!
        transportUnderTest.close().get(5, SECONDS)
    }

    @Test
    fun `dial should invoke preHandler before connection handlers`() {
        startListeners(transportUnderTest, 21100, 1)
        val address = localAddress(21100)
        val preHandlerCalled = AtomicBoolean(false)
        transportUnderTest.dial(address, {
            assert(preHandlerCalled.get())
        }, {
            preHandlerCalled.set(true)
        }).join()
    }

    @Test
    fun `listen should invoke preHandler before connection handlers`() {
        val address = localAddress(21100)
        val preHandlerCalled = AtomicBoolean(false)
        val connectionHandlerFuture = CompletableFuture<Unit>()
        transportUnderTest.listen(address, {
            if (preHandlerCalled.get()) {
                connectionHandlerFuture.complete(null)
            } else {
                connectionHandlerFuture.completeExceptionally(AssertionError("preHandler was not called"))
            }
        }, {
            preHandlerCalled.set(true)
        }).join()
        transportUnderTest.dial(address, { })

        connectionHandlerFuture.join()
    }

    @Test
    fun `cannot listen on closed transport`() {
        transportUnderTest.close()

        assertThrows(Libp2pException::class.java) {
            transportUnderTest.listen(
                localAddress(),
                nullConnHandler
            )
        }
    } // cannotListenOnClosedTransport

    @Test
    fun `cannot dial from a closed transport`() {
        transportUnderTest.close()

        assertThrows(Libp2pException::class.java) {
            transportUnderTest.dial(
                localAddress(21000),
                nullConnHandler
            )
        }
    } // cannotDialOnClosedTransport

    @Test
    fun `listen then close transport`() {
        val portNumber = 21000
        val listenerCount = 5
        startListeners(transportUnderTest, portNumber, listenerCount)

        transportUnderTest.close().get(5, SECONDS)
        assertEquals(0, transportUnderTest.activeListeners, "Not all listeners closed")
    }

    @Test
    fun `listen then unlisten`() {
        val portNumber = 21000
        val listenerCount = 5
        startListeners(transportUnderTest, portNumber, listenerCount)

        val unlistening = (0 until listenerCount).map {
            val unbindComplete = transportUnderTest.unlisten(
                localAddress(portNumber + it)
            )
            unbindComplete.handle { _, u -> logger.info("Unbound #$it", u) }
            logger.info("Unbinding #$it")
            unbindComplete
        }
        CompletableFuture.allOf(*unlistening.toTypedArray()).get(5, SECONDS)

        assertEquals(0, transportUnderTest.activeListeners, "Not all listeners closed")
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "TRAVIS", matches = "true")
    fun `dial then close client transport`() {
        val wsClient = connectClientAndServer(transportUnderTest, 50)

        logger.info("Closing now")
        wsClient.close().get(5, SECONDS)
        logger.info("Client transport closed")
        transportUnderTest.close().get(5, SECONDS)
        logger.info("Server transport closed")

        assertEquals(0, wsClient.activeConnections, "Not all client connections closed")
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "TRAVIS", matches = "true")
    fun `dial then close server transport`() {
        val wsClient = connectClientAndServer(transportUnderTest, 50)

        logger.info("Closing now")
        transportUnderTest.close().get(5, SECONDS)
        logger.info("Server transport closed")

        SECONDS.sleep(2) // let things settle
        assertEquals(0, wsClient.activeConnections, "Not all client connections closed")

        wsClient.close().get(5, SECONDS)
        logger.info("Client transport closed")
    }

    @Test
    fun `cannot dial an unresolveable address`() {
        assertThrows(Libp2pException::class.java) {
            transportUnderTest.dial(
                badAddress(),
                nullConnHandler
            )
        }
    }

    @Test
    fun `cannot listen on an unresolveable address`() {
        assertThrows(Libp2pException::class.java) {
            transportUnderTest.listen(
                badAddress(),
                nullConnHandler
            )
        }
    }
} // class TransportTests
