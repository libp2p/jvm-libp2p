package io.libp2p.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.etc.PROTOCOL
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

class StreamOverNetty(
    ch: Channel,
    override val connection: Connection
) : Stream, P2PChannelOverNetty(ch) {
    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    override val isInitiator = connection.isInitiator

    /**
     * Returns the [PeerId] of the remote peer [Connection] which this
     * [Stream] created on
     */
    override fun remotePeerId() = connection.secureSession().remoteId

    /**
     * @return negotiated protocol
     */
    override fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()

    override fun writeAndFlush(msg: Any) {
        nettyChannel.writeAndFlush(msg)
    }
}