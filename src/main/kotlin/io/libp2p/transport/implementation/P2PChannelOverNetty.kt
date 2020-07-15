package io.libp2p.transport.implementation

import io.libp2p.core.P2PChannel
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler

/**
 * The central class of the library which represents a channel where all communication
 * events happen. It is backed up by the Netty [Channel] where all the workflow happens
 *
 * This class might be thought of as a common denominator of [Connection] and [Stream] classes
 *
 * @param nettyChannel the underlying Netty channel
 */
abstract class P2PChannelOverNetty(
    val nettyChannel: Channel,
    override val isInitiator: Boolean
) : P2PChannel {
    private val closeCompletableFuture by lazy { nettyChannel.closeFuture().toVoidCompletableFuture() }

    override fun pushHandler(handler: ChannelHandler) {
        nettyChannel.pipeline().addLast(handler)
    }
    override fun pushHandler(name: String, handler: ChannelHandler) {
        nettyChannel.pipeline().addLast(name, handler)
    }

    override fun addHandlerBefore(baseName: String, name: String, handler: ChannelHandler) {
        nettyChannel.pipeline().addBefore(baseName, name, handler)
    }

    override fun close() = nettyChannel.close().toVoidCompletableFuture()

    override fun closeFuture() = closeCompletableFuture
    override fun toString(): String {
        return "P2PChannelOverNetty(nettyChannel=$nettyChannel, isInitiator=$isInitiator)"
    }
}
