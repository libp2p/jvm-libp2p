package io.libp2p.core.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.types.forward
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import java.util.concurrent.CompletableFuture

abstract class AbstractTransport(val upgrader: ConnectionUpgrader): Transport {
    
    protected fun createConnectionHandler(connHandler: ConnectionHandler, initiator: Boolean): Pair<ChannelHandler, CompletableFuture<Unit>> {
        val muxerFuture = CompletableFuture<Unit>()
        return object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                upgrader.establishSecureChannel(ch, initiator)
                    .thenCompose { upgrader.establishMuxer(ch, initiator) }
                    .thenApply {
                        val conn = Connection(ch)
                        val streamHandler = connHandler.apply(conn)
                        it.setInboundStreamHandler(streamHandler)
                    }
                    .forward(muxerFuture)
            }
        } to muxerFuture
    }
}