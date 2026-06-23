package io.libp2p.transport.quic

import io.libp2p.core.PeerId
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.security.tls.TlsPeerIdentity
import io.mockk.mockk
import io.netty.handler.codec.quic.QuicChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * Drives [QuicTransport.routeInboundHandshake] — the inbound (server-side) handshake routing
 * extracted from the channelActive handler — to verify the hole-punch identity guard rejects a
 * wrong-identity peer BEFORE the connection is exposed to application handlers. A full network
 * hole-punch e2e is impractical because the transport's dial binds an ephemeral source port and
 * dialAsListener only waits, so a mismatched peer cannot be made to connect from the registered
 * target tuple. Testing the seam directly proves the security-critical reject-before-exposure path.
 */
class QuicInboundHandshakeRoutingTest {

    private fun transport(): QuicTransport =
        QuicTransport.Ed25519(generateEd25519KeyPair().first, emptyList())

    private fun identity(): TlsPeerIdentity {
        val pubKey = generateEd25519KeyPair().second
        return TlsPeerIdentity(PeerId.fromPubKey(pubKey), pubKey)
    }

    @Test
    fun `rejects a wrong-identity hole punch before exposing the connection`() {
        val transport = transport()
        val inbound = identity()
        val future = CompletableFuture<QuicChannel>()
        // Pending hole punch targeting a DIFFERENT peer than the one that connected back.
        val pending = QuicTransport.PendingHolePunch(PeerId.random(), future)

        var prepared = false
        var closed = false
        var exposed = false

        transport.routeInboundHandshake(
            remoteIdentity = inbound,
            pending = pending,
            prepareConnection = { prepared = true },
            closeChannel = { closed = true },
            holePunchChannel = { error("must not hand a wrong-identity channel to the caller") },
            exposeConnection = { exposed = true }
        )

        // Channel closed, caller future failed, and crucially the connection was never prepared
        // nor exposed to application handlers.
        assertThat(closed).isTrue()
        assertThat(future).isCompletedExceptionally()
        assertThat(prepared).isFalse()
        assertThat(exposed).isFalse()
    }

    @Test
    fun `hands a matching-identity hole punch to the waiting caller`() {
        val transport = transport()
        val inbound = identity()
        val future = CompletableFuture<QuicChannel>()
        // Pending hole punch whose target matches the peer that connected back.
        val pending = QuicTransport.PendingHolePunch(inbound.peerId, future)
        val channel = mockk<QuicChannel>()

        var prepared = false
        var closed = false
        var exposed = false

        transport.routeInboundHandshake(
            remoteIdentity = inbound,
            pending = pending,
            prepareConnection = { prepared = true },
            closeChannel = { closed = true },
            holePunchChannel = { channel },
            exposeConnection = { exposed = true }
        )

        assertThat(prepared).isTrue()
        assertThat(future.getNow(null)).isSameAs(channel)
        assertThat(closed).isFalse()
        // Hole-punch caller is handed the channel directly; it is not exposed via connHandler.
        assertThat(exposed).isFalse()
    }

    @Test
    fun `exposes a normal inbound connection when no hole punch is pending`() {
        val transport = transport()
        val inbound = identity()

        var prepared = false
        var closed = false
        var exposed = false

        transport.routeInboundHandshake(
            remoteIdentity = inbound,
            pending = null,
            prepareConnection = { prepared = true },
            closeChannel = { closed = true },
            holePunchChannel = { error("no hole punch is pending") },
            exposeConnection = { exposed = true }
        )

        assertThat(prepared).isTrue()
        assertThat(exposed).isTrue()
        assertThat(closed).isFalse()
    }

    @Test
    fun `closes the connection when the handler rejects a normal inbound connection`() {
        val transport = transport()
        val inbound = identity()

        var closed = false

        // The application connection handler may reject an inbound connection by throwing
        // (e.g. PeerAlreadyConnectedException for a duplicate/simultaneous connection). That
        // exception must NOT escape routeInboundHandshake: in the live server pipeline the
        // handshake-waiter handler has already removed itself by the time the connection is
        // exposed, so an escaping exception reaches the Netty pipeline tail and logs a noisy
        // "exceptionCaught reached tail of pipeline" warning. Instead the rejected connection
        // must be closed, mirroring the dial paths.
        assertThatCode {
            transport.routeInboundHandshake(
                remoteIdentity = inbound,
                pending = null,
                prepareConnection = { },
                closeChannel = { closed = true },
                holePunchChannel = { error("no hole punch is pending") },
                exposeConnection = { throw RuntimeException("Already connected to peer") }
            )
        }.doesNotThrowAnyException()

        assertThat(closed).isTrue()
    }
}
