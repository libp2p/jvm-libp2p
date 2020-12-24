package io.libp2p.core

import java.util.concurrent.CompletableFuture

/**
 * The central entry point for every protocol which is responsible for initializing [P2PChannel]
 */
interface P2PChannelHandler<out TController> {

    /**
     * Should initialize the underlying Netty [io.netty.channel.Channel] **synchronously**
     * and **on the calling thread**
     * Returns the [Future] which is completed with the protocol [TController]
     * when all necessary protocol negotiations are done.
     */
    fun initChannel(ch: P2PChannel): CompletableFuture<out TController>

    @JvmDefault
    fun toStreamHandler(): StreamHandler<TController> = StreamHandler { stream -> initChannel(stream) }
}
