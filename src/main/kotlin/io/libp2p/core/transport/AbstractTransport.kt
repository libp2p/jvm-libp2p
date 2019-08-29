package io.libp2p.core.transport

import io.libp2p.core.CONNECTION
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.IS_INITIATOR
import io.libp2p.core.types.forward
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

abstract class AbstractTransport(val upgrader: ConnectionUpgrader) : Transport {

    protected fun createConnectionHandler(
        connHandler: ConnectionHandler,
        initiator: Boolean
    ): Pair<ChannelHandler, CompletableFuture<Connection>> {

        val connFuture = CompletableFuture<Connection>()
        return nettyInitializer { ch ->
            val connection = Connection(ch)
            ch.attr(IS_INITIATOR).set(initiator)
            ch.attr(CONNECTION).set(connection)
            upgrader.establishSecureChannel(ch)
                .thenCompose {
                    upgrader.establishMuxer(ch)
                }.thenApply {
                    connHandler.handleConnection(connection)
                    connection
                }
                .forward(connFuture)
        } to connFuture
    }
}