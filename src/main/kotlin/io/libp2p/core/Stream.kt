package io.libp2p.core

import java.util.concurrent.CompletableFuture

/**
 * Represents a multiplexed stream over wire connection
 */
interface Stream : P2PChannel {
    val connection: Connection

    /**
     * Returns the [PeerId] of the remote peer [Connection] which this
     * [Stream] created on
     */
    fun remotePeerId(): PeerId

    /**
     * @return negotiated protocol
     */
    fun getProtocol(): CompletableFuture<String>

    fun writeAndFlush(msg: Any)
}
