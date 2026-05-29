package io.libp2p.network

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class NetworkImplTest {

    @Test
    fun `connect cancels pending dials after first successful dial`() {
        val peerId = PeerId.random()
        val pendingDial = CompletableFuture<Connection>()
        val successfulConnection = TestConnection()
        val network = NetworkImpl(
            listOf(
                TestTransport(Protocol.IP6, pendingDial),
                TestTransport(Protocol.IP4, CompletableFuture.completedFuture(successfulConnection))
            ),
            ConnectionHandler { }
        )

        val connection = network.connect(
            peerId,
            Multiaddr("/ip6/::1/tcp/4001"),
            Multiaddr("/ip4/127.0.0.1/tcp/4001")
        ).get(5, SECONDS)

        assertSame(successfulConnection, connection)
        assertTrue(pendingDial.isCancelled, "Pending IPv6 dial was not cancelled")
    }

    @Test
    fun `connect cancels pending dials when aggregate future is cancelled`() {
        val peerId = PeerId.random()
        val pendingIpv6Dial = CompletableFuture<Connection>()
        val pendingIpv4Dial = CompletableFuture<Connection>()
        val network = NetworkImpl(
            listOf(
                TestTransport(Protocol.IP6, pendingIpv6Dial),
                TestTransport(Protocol.IP4, pendingIpv4Dial)
            ),
            ConnectionHandler { }
        )

        val connect = network.connect(
            peerId,
            Multiaddr("/ip6/::1/tcp/4001"),
            Multiaddr("/ip4/127.0.0.1/tcp/4001")
        )

        assertTrue(connect.cancel(true))
        assertTrue(pendingIpv6Dial.isCancelled, "Pending IPv6 dial was not cancelled")
        assertTrue(pendingIpv4Dial.isCancelled, "Pending IPv4 dial was not cancelled")
    }

    @Test
    fun `connect cancels pending dials when aggregate future times out`() {
        val peerId = PeerId.random()
        val pendingIpv6Dial = CompletableFuture<Connection>()
        val pendingIpv4Dial = CompletableFuture<Connection>()
        val network = NetworkImpl(
            listOf(
                TestTransport(Protocol.IP6, pendingIpv6Dial),
                TestTransport(Protocol.IP4, pendingIpv4Dial)
            ),
            ConnectionHandler { }
        )

        val connect = network.connect(
            peerId,
            Multiaddr("/ip6/::1/tcp/4001"),
            Multiaddr("/ip4/127.0.0.1/tcp/4001")
        )

        assertTrue(connect.completeExceptionally(TimeoutException()))
        assertTrue(pendingIpv6Dial.isCancelled, "Pending IPv6 dial was not cancelled")
        assertTrue(pendingIpv4Dial.isCancelled, "Pending IPv4 dial was not cancelled")
    }

    private class TestTransport(
        private val protocol: Protocol,
        private val dialResult: CompletableFuture<Connection>
    ) : Transport {
        override val activeListeners: Int = 0
        override val activeConnections: Int = 0

        override fun listenAddresses(): List<Multiaddr> = emptyList()

        override fun handles(addr: Multiaddr): Boolean = addr.has(protocol)

        override fun initialize() = Unit

        override fun close(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

        override fun listen(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

        override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

        override fun dial(
            addr: Multiaddr,
            connHandler: ConnectionHandler,
            preHandler: ChannelVisitor<P2PChannel>?
        ): CompletableFuture<Connection> = dialResult
    }

    private class TestConnection : Connection {
        private val closeFuture = CompletableFuture<Unit>()

        override val isInitiator: Boolean = true

        override fun pushHandler(handler: io.netty.channel.ChannelHandler) = Unit

        override fun pushHandler(name: String, handler: io.netty.channel.ChannelHandler) = Unit

        override fun addHandlerBefore(baseName: String, name: String, handler: io.netty.channel.ChannelHandler) = Unit

        override fun close(): CompletableFuture<Unit> {
            closeFuture.complete(Unit)
            return closeFuture
        }

        override fun closeFuture(): CompletableFuture<Unit> = closeFuture

        override fun muxerSession(): StreamMuxer.Session = throw UnsupportedOperationException()

        override fun secureSession(): SecureChannel.Session = throw UnsupportedOperationException()

        override fun transport(): Transport = throw UnsupportedOperationException()

        override fun localAddress(): Multiaddr = throw UnsupportedOperationException()

        override fun remoteAddress(): Multiaddr = throw UnsupportedOperationException()
    }
}
