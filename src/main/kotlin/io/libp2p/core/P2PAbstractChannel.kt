package io.libp2p.core

import io.libp2p.etc.IS_INITIATOR
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel

/**
 * The central class of the library which represents a channel where all communication
 * events happen. It is backed up by the Netty [Channel] where all the workflow happens
 *
 * This class might be thought of as a common denominator of [Connection] and [Stream] classes
 *
 * @param nettyChannel the underlying Netty channel
 */
abstract class P2PAbstractChannel(val nettyChannel: Channel) {

    /**
     * Indicates whether this peer is ether _initiator_ or _responder_ of the underlying channel
     * Most of the protocols behave either as a _client_ or _server_ correspondingly depending
     * on this flag
     */
    val isInitiator by lazy {
        nettyChannel.attr(IS_INITIATOR)?.get() ?: throw InternalErrorException("Internal error: missing channel attribute IS_INITIATOR")
    }

    /**
     * Returns the [CompletableFuture] which is completed when this channel is closed
     */
    fun closeFuture() = nettyChannel.closeFuture().toVoidCompletableFuture()
}
