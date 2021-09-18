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

    /**
     * Resets the [Stream]. That means the stream is to be abruptly terminated.
     * This method is basically used when any error occurs while communicating
     * @see close
     * @see closeWrite
     */
    fun reset() = close()

    /**
     * Equivalent to [reset].
     * To close the [Stream] just from the local side use [closeWrite]
     */
    override fun close(): CompletableFuture<Unit>

    /**
     * Closes the local side of the [Stream].
     * [closeWrite] means that local party completed writing to the [Stream]
     * @see io.libp2p.etc.util.netty.mux.RemoteWriteClosed to be notified on the remote
     * stream write side closing
     */
    fun closeWrite(): CompletableFuture<Unit>
}
