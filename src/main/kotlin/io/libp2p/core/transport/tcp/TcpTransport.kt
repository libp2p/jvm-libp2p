package io.libp2p.core.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.transport.Transport
import io.libp2p.core.types.toCompletableFuture
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import java.util.concurrent.CompletableFuture

/**
 * The TCP transport can establish libp2p connections via TCP endpoints.
 *
 * Given that TCP by itself is not authenticated, encrypted, nor multiplexed, this transport uses the upgrader to
 * shim those capabilities via dynamic negotiation.
 */
class TcpTransport(val upgrader: ConnectionUpgrader) : Transport {
    private var server: ServerBootstrap? = ServerBootstrap()
    private var client: Bootstrap = Bootstrap()

    // Initializes the server and client fields, preparing them to establish outbound connections (client)
    // and to accept inbound connections (server).
    override fun initialize() {
    }

    // Checks if this transport can handle this multiaddr. It should return true for multiaddrs containing `tcp` atoms.
    override fun handles(addr: Multiaddr): Boolean {
        return addr.components
            .any { pair -> pair.first == Protocol.TCP }
    }

    // Closes this transport entirely, aborting all ongoing connections and shutting down any listeners.
    override fun close(): CompletableFuture<Void> {
        TODO("not implemented")
    }

    override fun listen(addr: Multiaddr): CompletableFuture<Void> {
        TODO("not implemented")
    }

    override fun unlisten(addr: Multiaddr): CompletableFuture<Void> {
        TODO("not implemented")
    }

    override fun dial(addr: Multiaddr): CompletableFuture<Connection> =
        client.connect().toCompletableFuture()
            .thenCompose { upgrader.establishMuxer(it).toCompletableFuture() }
            .thenApply { Connection(it) }
}