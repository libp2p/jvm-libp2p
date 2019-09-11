package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.transport.Transport
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.IS_INITIATOR
import io.libp2p.etc.types.forward
import io.libp2p.etc.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

abstract class AbstractTransport(val upgrader: ConnectionUpgrader) :
    Transport {

    protected fun createConnectionHandler(
        connHandler: ConnectionHandler,
        initiator: Boolean,
        remotePeerId: PeerId? = null
    ): Pair<ChannelHandler, CompletableFuture<Connection>> {
        val connFuture = CompletableFuture<Connection>()
        return nettyInitializer { ch ->
            val connection = Connection(ch)
            ch.attr(IS_INITIATOR).set(initiator)
            ch.attr(CONNECTION).set(connection)
            upgrader.establishSecureChannel(ch, remotePeerId)
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