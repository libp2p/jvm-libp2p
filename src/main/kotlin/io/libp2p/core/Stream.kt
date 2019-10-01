package io.libp2p.core

import io.libp2p.etc.PROTOCOL
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

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

/**
 * Represents a multiplexed stream over wire connection
 */
class StreamOverNetty(
    ch: Channel,
    override val connection: Connection
) : Stream, P2PAbstractChannel(ch) {
    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    /**
     * Returns the [PeerId] of the remote peer [Connection] which this
     * [Stream] created on
     */
    override fun remotePeerId() = connection.secureSession.remoteId

    /**
     * @return negotiated protocol
     */
    override fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()

    override fun writeAndFlush(msg: Any) {
        nettyChannel.writeAndFlush(msg)
    }
}