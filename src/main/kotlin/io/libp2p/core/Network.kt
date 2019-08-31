package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import java.util.concurrent.CompletableFuture

/**
 * The networkConfig component handles all networkConfig affairs, particularly listening on endpoints and dialing peers.
 */
interface Network {
    val transports: List<Transport>
    val connectionHandler: ConnectionHandler

    val connections: List<Connection>

    fun listen(addr: Multiaddr): CompletableFuture<Unit>
    fun unlisten(addr: Multiaddr): CompletableFuture<Unit>

    fun connect(id: PeerId, vararg addrs: Multiaddr): CompletableFuture<Connection>
    fun disconnect(conn: Connection): CompletableFuture<Unit>

    fun close(): CompletableFuture<Unit>
}
