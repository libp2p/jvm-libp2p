package io.libp2p.core

import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.protocol.ProtocolMessageHandlerAdapter
import java.util.concurrent.CompletableFuture

/**
 * Represents a multiplexed stream over wire connection
 */
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

    fun <TMessage> pushHandler(protocolHandler: ProtocolMessageHandler<TMessage>) {
        pushHandler(ProtocolMessageHandlerAdapter(this, protocolHandler))
    }

    fun writeAndFlush(msg: Any)
}
