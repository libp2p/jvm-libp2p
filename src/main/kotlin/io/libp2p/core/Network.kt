package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The networkConfig component handles all networkConfig affairs, particularly listening on endpoints and dialing peers.
 */
class Network(private val transports: List<Transport>, private val config: Config) {
    /**
     * The connection table.
     */
    private val connections: Map<PeerId, Connection> = ConcurrentHashMap()

    data class Config(val listenAddrs: List<Multiaddr>)

    init {
        transports.forEach(Transport::initialize)
    }

    fun start(): CompletableFuture<Void> {
        val futs = mutableListOf<CompletableFuture<Void>>()
        // start listening on all specified addresses.
        config.listenAddrs.forEach { addr ->
            // find the appropriate transport.
            val dialTpt = transports.firstOrNull { tpt -> tpt.handles(addr) }
                ?: throw RuntimeException("no transport to handle addr: $addr")
            futs += dialTpt.listen(addr)
        }
        return CompletableFuture.allOf(*futs.toTypedArray())
    }

    fun close(): CompletableFuture<Void> {
        val futs = transports.map(Transport::close)
        return CompletableFuture.allOf(*futs.toTypedArray())
    }

    fun connect(id: PeerId, addrs: List<Multiaddr>): CompletableFuture<Connection> {
        // we already have a connection for this peer, short circuit.
        connections[id]?.apply { return CompletableFuture.completedFuture(this) }

        // 1. check that some transport can dial at least one addr.
        // 2. trigger dials in parallel via all transports.
        // 3. when the first dial succeeds, cancel all pending dials and return the connection.
        // 4. if no emitted dial succeeds, or if we time out, fail the future. make sure to cancel
        //    pending dials to avoid leaking.
        return CompletableFuture()
    }

}