package io.libp2p.transport.tcp

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
    protected val nettyChannel: Channel
) {
    fun pushHandler(handler: ChannelHandler) {
        nettyChannel.pipeline().addLast(handler)
    }
    fun pushHandler(name: String, handler: ChannelHandler) {
        nettyChannel.pipeline().addLast(name, handler)
    }

    fun addHandlerBefore(baseName: String, name: String, handler: ChannelHandler) {
        nettyChannel.pipeline().addBefore(baseName, name, handler)
    }

    fun close() = nettyChannel.close().toVoidCompletableFuture()

    fun closeFuture() = nettyChannel.closeFuture().toVoidCompletableFuture()
}
