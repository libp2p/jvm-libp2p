package io.libp2p.transport.implementation

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.P2PChannel
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
    private val remotePeerId: PeerId? = null,
    private val preHandler: ChannelVisitor<P2PChannel>? = null
) : ChannelInitializer<Channel>() {
    val connectionEstablished = CompletableFuture<Connection>()

    override fun initChannel(ch: Channel) {
        remotePeerId?.also { ch.attr(REMOTE_PEER_ID).set(it) }
        val connection = ConnectionOverNetty(ch, transport, initiator)

        preHandler?.also { it.visit(connection) }

        upgrader.establishSecureChannel(connection)
            .thenCompose {
                connection.setSecureSession(it)
                upgrader.establishMuxer(it.nextProto, connection)
            }.thenApply {
                connection.setMuxerSession(it)
                connHandler.handleConnection(connection)
                connection
            }
            .forward(connectionEstablished)
    } // initChannel
} // ConnectionBuilder
