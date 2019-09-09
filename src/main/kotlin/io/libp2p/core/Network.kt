package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
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

    fun connect(vararg addrs: Multiaddr): CompletableFuture<Connection> {
        val peerIdSet = addrs.map {
            it.getStringComponents().find { it.first == Protocol.P2P }?.second
                ?: throw Libp2pException("Multiaddress should contain /p2p/<peerId> component")
        }.toSet()
        if (peerIdSet.size != 1) throw Libp2pException("All multiaddresses should nave the same peerId")
        return connect(PeerId.fromBase58(peerIdSet.first()), *addrs)
    }

    fun connect(id: PeerId, vararg addrs: Multiaddr): CompletableFuture<Connection>
    fun disconnect(conn: Connection): CompletableFuture<Unit>

    fun close(): CompletableFuture<Unit>
}
