package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Verifies the SecureChannel.Session invariant `PeerId.fromPubKey(remotePubKey) == remoteId`
 * for QUIC connections on both the client and server sides.
 *
 * Before the fix in this commit, QuicTransport exposed the ephemeral TLS subject public key
 * (or, on the client, the in-multihash-encoded peer key only for `identity` digests) rather
 * than the libp2p host public key embedded in the certificate extension. That violated the
 * contract SecIO/Noise/Plaintext uphold and broke any caller that relies on it
 * (e.g. signed-record verification).
 */
class QuicRemotePubKeyIdentityTest {

    private fun randomPort(): Int = Random().nextInt(20_000) + 10_000

    private class CapturingConnectionHandler : ConnectionHandler {
        val connectionFuture = CompletableFuture<Connection>()
        override fun handleConnection(conn: Connection) {
            connectionFuture.complete(conn)
        }
    }

    @Test
    fun `remotePubKey hashes to remoteId on both sides of a QUIC handshake`() {
        val listenAddress = "/ip4/127.0.0.1/udp/${randomPort()}/quic-v1"

        val clientHandler = CapturingConnectionHandler()
        val serverHandler = CapturingConnectionHandler()

        val clientHost = HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .builderModifier { b -> b.connectionHandlers.add(clientHandler) }
            .build()

        val serverHost = HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .listen(listenAddress)
            .builderModifier { b -> b.connectionHandlers.add(serverHandler) }
            .build()

        try {
            clientHost.start().get(5, TimeUnit.SECONDS)
            serverHost.start().get(5, TimeUnit.SECONDS)

            clientHost.network.connect(serverHost.peerId, Multiaddr(listenAddress))
                .get(10, TimeUnit.SECONDS)

            val clientConn = clientHandler.connectionFuture.get(10, TimeUnit.SECONDS)
            val serverConn = serverHandler.connectionFuture.get(10, TimeUnit.SECONDS)

            val clientSession = clientConn.secureSession()
            val serverSession = serverConn.secureSession()

            // Server-side: the secure session must expose the libp2p host pubkey of the client.
            assertThat(serverSession.remoteId).isEqualTo(clientHost.peerId)
            assertThat(PeerId.fromPubKey(serverSession.remotePubKey)).isEqualTo(serverSession.remoteId)

            // Client-side: the secure session must expose the libp2p host pubkey of the server.
            assertThat(clientSession.remoteId).isEqualTo(serverHost.peerId)
            assertThat(PeerId.fromPubKey(clientSession.remotePubKey)).isEqualTo(clientSession.remoteId)
        } finally {
            clientHost.stop().get(5, TimeUnit.SECONDS)
            serverHost.stop().get(5, TimeUnit.SECONDS)
        }
    }
}
