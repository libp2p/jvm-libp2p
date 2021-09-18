package io.libp2p.core.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.multiformats.Multiaddr
import java.util.concurrent.CompletableFuture

/**
 * A Transport represents an adapter to integrate (typically) a L2, L3 or L7 protocol into libp2p, to allow incoming
 * and outgoing connections to be established.
 *
 * TODO:
 *   * Expose transport qualities/attributes (dial only, listen only, reliable, connectionful, costly, etc.)
 */
interface Transport {
    val activeListeners: Int
    val activeConnections: Int

    fun listenAddresses(): List<Multiaddr>

    /**
     * Verifies whether this transport is capable of handling this multiaddr.
     */
    fun handles(addr: Multiaddr): Boolean

    /**
     * Performs any preparation, warm-up or initialisation to activate this transport.
     */
    fun initialize()

    /**
     * Stops the transport entirely, closing down all ongoing connections, outbound dials, and listening endpoints.
     */
    fun close(): CompletableFuture<Unit>

    /**
     * Makes this transport listen on this multiaddr. The future completes once the endpoint is effectively listening.
     */
    fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit>

    /**
     * Makes this transport stop listening on this multiaddr. Any connections maintained from this source host and port
     * will be disconnected as a result. The future completes once the endpoint and all its connections are effectively
     * stopped.
     */
    fun unlisten(addr: Multiaddr): CompletableFuture<Unit>

    /**
     * Dials the specified multiaddr and returns a promise of a Connection.
     */
    fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection>

    /**
     * Dials the specified multiaddr and returns a promise of a Connection.
     */
    fun dial(addr: Multiaddr): CompletableFuture<Connection> = dial(addr, { })
}
