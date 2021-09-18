package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.transport.Transport
import io.libp2p.etc.REMOTE_PEER_ID
import io.libp2p.etc.types.forward
import io.libp2p.transport.ConnectionUpgrader
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import java.util.concurrent.CompletableFuture

class ConnectionBuilder(
    private val transport: Transport,
    private val upgrader: ConnectionUpgrader,
    private val connHandler: ConnectionHandler,
    private val initiator: Boolean,
    private val remotePeerId: PeerId? = null
) : ChannelInitializer<Channel>() {
    val connectionEstablished = CompletableFuture<Connection>()

    override fun initChannel(ch: Channel) {
        val connection = ConnectionOverNetty(ch, transport, initiator)
        remotePeerId?.also { ch.attr(REMOTE_PEER_ID).set(it) }

        upgrader.establishSecureChannel(connection)
            .thenCompose {
                connection.setSecureSession(it)
                upgrader.establishMuxer(connection)
            }.thenApply {
                connection.setMuxerSession(it)
                connHandler.handleConnection(connection)
                connection
            }
            .forward(connectionEstablished)
    } // initChannel
} // ConnectionBuilder
