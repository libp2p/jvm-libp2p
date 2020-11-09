package io.libp2p.core

import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

interface P2PChannel {
    /**
     * Indicates whether this peer is ether _initiator_ or _responder_ of the underlying channel
     * Most of the protocols behave either as a _client_ or _server_ correspondingly depending
     * on this flag
     */
    val isInitiator: Boolean

    /**
     * Inserts [ChannelHandler]s at the last position of this pipeline.
     */
    fun pushHandler(handler: ChannelHandler)

    /**
     * Appends a [ChannelHandler] at the last position of this pipeline.
     */
    fun pushHandler(name: String, handler: ChannelHandler)

    /**
     * Inserts a [ChannelHandler] before an existing handler of this
     * pipeline.
     */
    fun addHandlerBefore(baseName: String, name: String, handler: ChannelHandler)

    /**
     * Closes the channel. Returns a [CompletableFuture] which completes when the
     * channel has closed
     */
    fun close(): CompletableFuture<Unit>

    /**
     * Returns the [CompletableFuture] which is completed when this channel is closed
     */
    fun closeFuture(): CompletableFuture<Unit>
}
