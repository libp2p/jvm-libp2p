package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
import java.util.concurrent.CompletableFuture

/**
 * The networkConfig component handles all networkConfig affairs, particularly listening on endpoints and dialing peers.
 */
interface Network {
    /**
     * Transports supported by this network
     */
    val transports: List<Transport>

    /**
     * The handler which all established connections are initialized with
     */
    val connectionHandler: ConnectionHandler

    /**
     * The list of active connections
     */
    val connections: List<Connection>

    /**
     * Starts listening on specified address. The returned future asynchronously
     * notifies on success or error
     * All the incoming connections are handled with [connectionHandler]
     */
    fun listen(addr: Multiaddr): CompletableFuture<Unit>

    /**
     * Stops listening on specified address. The returned future asynchronously
     * notifies on success or error
     */
    fun unlisten(addr: Multiaddr): CompletableFuture<Unit>

    /**
     * Connects to a remote peer
     * This is a shortcut to [connect(PeerId, Multiaddr)] for the
     * cases when [Multiaddr] contains [/p2p] component which contains remote [PeerId]
     *
     * @throws Libp2pException if [/p2p] component is missing or addresses has different [/p2p] values
     * @throws TransportNotSupportedException if any of [addrs] represents the transport which is not supported
     */
    fun connect(vararg addrs: Multiaddr): CompletableFuture<Connection> {
        val peerIdSet = addrs.map {
            it.getStringComponent(Protocol.P2P)
                ?: throw Libp2pException("Multiaddress should contain /p2p/<peerId> component")
        }.toSet()
        if (peerIdSet.size != 1) throw Libp2pException("All multiaddresses should nave the same peerId")
        return connect(PeerId.fromBase58(peerIdSet.first()), *addrs)
    }

    /**
     * Tries ot connect to the remote peer with [id] PeerId by specified addresses
     * If connection to this peer already exist, returns existing connection
     * Else tries to connect the peer by all supplied addresses in parallel
     * and completes the returned [Future] when any of connections succeeds
     *
     * If the connection is established it is handled by [connectionHandler]
     *
     * @throws TransportNotSupportedException if any of [addrs] represents the transport which is not supported
     */
    fun connect(id: PeerId, vararg addrs: Multiaddr): CompletableFuture<Connection>

    /**
     * Closes the specified [Connection]
     */
    fun disconnect(conn: Connection): CompletableFuture<Unit>

    /**
     * Closes all listening endpoints
     * Closes all active connections
     */
    fun close(): CompletableFuture<Unit>
}
