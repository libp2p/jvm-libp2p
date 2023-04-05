package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import io.netty.channel.EventLoop
import java.util.concurrent.CompletableFuture

class StreamOverNetty(
    ch: Channel,
    override val connection: Connection,
    initiator: Boolean
) : Stream, P2PChannelOverNetty(ch, initiator) {

    val group: EventLoop
    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
        group = ch.eventLoop()
    }

    override fun eventLoop() = group

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

    override fun closeWrite(): CompletableFuture<Unit> {
        return nettyChannel.disconnect().toVoidCompletableFuture()
    }
}
