package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import io.libp2p.core.types.anyComplete
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The networkConfig component handles all networkConfig affairs, particularly listening on endpoints and dialing peers.
 */
class Network(
    private val transports: List<Transport>,
    private val config: Config
) {
    /**
     * The connection table.
     */
    lateinit var connectionHandler: ConnectionHandler
    lateinit var streamHandler: StreamHandler

    val connections: MutableMap<PeerId, Connection> = ConcurrentHashMap()

    data class Config(val listenAddrs: List<Multiaddr>)

    init {
        transports.forEach(Transport::initialize)
    }

    fun start(): CompletableFuture<Unit> {
        val futs = mutableListOf<CompletableFuture<Unit>>()
        // start listening on all specified addresses.
        config.listenAddrs.forEach { addr ->
            // find the appropriate transport.
            val transport = transports.firstOrNull { tpt -> tpt.handles(addr) }
                ?: throw RuntimeException("no transport to handle addr: $addr")
            futs += transport.listen(addr, connectionHandler, streamHandler)
        }
        return CompletableFuture.allOf(*futs.toTypedArray()).thenApply { }
    }

    fun close(): CompletableFuture<Unit> {
        val futs = transports.map(Transport::close)
        return CompletableFuture.allOf(*futs.toTypedArray()).thenApply { }
    }

    fun connect(id: PeerId, vararg addrs: Multiaddr): CompletableFuture<Connection> {

        // we already have a connection for this peer, short circuit.
        connections[id]?.apply { return CompletableFuture.completedFuture(this) }

        // 1. check that some transport can dial at least one addr.
        // 2. trigger dials in parallel via all transports.
        // 3. when the first dial succeeds, cancel all pending dials and return the connection. // TODO cancel
        // 4. if no emitted dial succeeds, or if we time out, fail the future. make sure to cancel
        //    pending dials to avoid leaking.
        val connectionFuts = addrs.mapNotNull { addr ->
            transports.firstOrNull { tpt -> tpt.handles(addr) }?.let { addr to it }
        }.map {
            it.second.dial(it.first, streamHandler)
        }
        return anyComplete(connectionFuts)
            .thenApply {
                connections[id] = it
                connectionHandler.handleConnection(it)
                it
            }
    }
}