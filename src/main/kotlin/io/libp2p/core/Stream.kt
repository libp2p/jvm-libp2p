package io.libp2p.core

import io.libp2p.etc.PROTOCOL
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

/**
 * Represents a multiplexed stream over wire connection
 */
class Stream(ch: Channel, val conn: Connection) : P2PAbstractChannel(ch), P2PChannel {

    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    /**
     * Returns the [PeerId] of the remote peer [Connection] which this
     * [Stream] created on
     */
    fun remotePeerId() = conn.secureSession.remoteId

    /**
     * @return negotiated protocol
     */
    fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()
}