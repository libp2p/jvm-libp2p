package io.libp2p.simulate.stream

import io.libp2p.core.Connection
import io.libp2p.core.Stream
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.transport.implementation.P2PChannelOverNetty
import io.netty.channel.Channel
import io.netty.channel.EventLoop
import java.util.concurrent.CompletableFuture

class Libp2pStreamImpl(
    override val connection: Connection,
    ch: Channel,
    initiator: Boolean
) : P2PChannelOverNetty(ch, initiator), Stream {

    init {
        nettyChannel.attr(PROTOCOL).set(CompletableFuture())
    }

    override fun remotePeerId() = connection.secureSession().remoteId

    override fun getProtocol(): CompletableFuture<String> = nettyChannel.attr(PROTOCOL).get()

    override fun writeAndFlush(msg: Any) {
        nettyChannel.writeAndFlush(msg)
    }

    override fun closeWrite(): CompletableFuture<Unit> =
        nettyChannel.disconnect().toVoidCompletableFuture()

    override fun eventLoop(): EventLoop {
        TODO("Not yet implemented")
    }
}
