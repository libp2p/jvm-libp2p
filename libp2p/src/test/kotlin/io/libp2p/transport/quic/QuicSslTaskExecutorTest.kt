package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.netty.util.concurrent.ImmediateEventExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Regression test for https://github.com/libp2p/jvm-libp2p/issues/523: a shared multi-thread
 * `sslTaskExecutor` lets delegated TLS tasks run on a thread other than the connection's own
 * event loop. Netty QUIC only
 * synchronizes task *retrieval* against connection teardown, not the task's native execution, so
 * a task left running on a background thread can race a concurrent close and touch freed native
 * SSL/quiche state (observed as a SIGSEGV in `BoringSSL.SSL_getTask`).
 *
 * The fix configures `ImmediateEventExecutor.INSTANCE` so delegated tasks always run inline on
 * the calling (event-loop) thread. This test asserts that configuration sticks on both the
 * client-dial and server-accept codecs, rather than trying to reproduce the underlying native
 * race directly.
 */
class QuicSslTaskExecutorTest {

    private fun randomPort(): Int = Random().nextInt(20_000) + 10_000

    private class CapturingConnectionHandler : ConnectionHandler {
        val connectionFuture = CompletableFuture<Connection>()
        override fun handleConnection(conn: Connection) {
            connectionFuture.complete(conn)
        }
    }

    private fun sslTaskExecutorOf(connection: Connection): Executor {
        val quicChannelClass = Class.forName("io.netty.handler.codec.quic.QuicheQuicChannel")
        val field = quicChannelClass.getDeclaredField("sslTaskExecutor")
        field.isAccessible = true
        return field.get((connection as ConnectionOverNetty).nettyChannel) as Executor
    }

    @Test
    fun `client and server QUIC connections run delegated TLS tasks inline, not on a shared pool`() {
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

            assertThat(sslTaskExecutorOf(clientConn)).isSameAs(ImmediateEventExecutor.INSTANCE)
            assertThat(sslTaskExecutorOf(serverConn)).isSameAs(ImmediateEventExecutor.INSTANCE)
        } finally {
            clientHost.stop().get(5, TimeUnit.SECONDS)
            serverHost.stop().get(5, TimeUnit.SECONDS)
        }
    }
}
