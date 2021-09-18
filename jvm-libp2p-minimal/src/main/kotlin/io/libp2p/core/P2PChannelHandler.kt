package io.libp2p.core

import io.libp2p.etc.BroadcastChannelVisitor
import java.util.concurrent.CompletableFuture

/**
 * The central entry point for every protocol which is responsible for initializing [P2PChannel]
 */
fun interface P2PChannelHandler<TController> {

    /**
     * Should initialize the underlying Netty [io.netty.channel.Channel] **synchronously**
     * and **on the calling thread**
     * Returns the [Future] which is completed with the protocol [TController]
     * when all necessary protocol negotiations are done.
     */
    fun initChannel(ch: P2PChannel): CompletableFuture<TController>

    @JvmDefault
    fun toStreamHandler(): StreamHandler<TController> = StreamHandler { stream -> initChannel(stream) }
}

fun interface ChannelVisitor<TChannel : P2PChannel> {

    fun visit(channel: TChannel)

    @JvmDefault
    fun toChannelHandler(): P2PChannelHandler<Unit> = P2PChannelHandler {
        visit(it as TChannel)
        CompletableFuture.completedFuture(Unit)
    }

    interface Broadcast<TChannel : P2PChannel> : ChannelVisitor<TChannel>, MutableList<ChannelVisitor<TChannel>>

    companion object {
        fun <TChannel : P2PChannel> createBroadcast(vararg handlers: ChannelVisitor<TChannel>) =
            BroadcastChannelVisitor<TChannel>().also { it += handlers }
    }
}
