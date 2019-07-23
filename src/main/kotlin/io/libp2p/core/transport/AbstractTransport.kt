package io.libp2p.core.transport

import io.libp2p.core.Connection
import io.libp2p.core.StreamHandler
import io.libp2p.core.types.forward
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

abstract class AbstractTransport(val upgrader: ConnectionUpgrader) : Transport {

    protected fun createConnectionHandler(
        streamHandler: StreamHandler,
        initiator: Boolean
    ): Pair<ChannelHandler, CompletableFuture<Connection>> {

        val connFuture = CompletableFuture<Connection>()
        return nettyInitializer { ch ->
            upgrader.establishSecureChannel(ch, initiator)
                .thenCompose {
                    upgrader.establishMuxer(ch, streamHandler, initiator)
                }
                .thenApply {
                    Connection(ch)
                }
                .forward(connFuture)
        } to connFuture
    }
}