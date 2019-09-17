package io.libp2p.core

import java.util.concurrent.CompletableFuture

/**
 * The central entry point for every protocol which is responsible for initializing [P2PAbstractChannel]
 */
interface P2PAbstractHandler<out TController> {

    /**
     * Should initialize the underlying Netty [io.netty.channel.Channel] **synchronously**
     * and **on the calling thread**
     * Returns the [Future] which is completed with the protocol [TController]
     * when all necessary protocol negotiations are done.
     */
    fun initChannel(ch: P2PAbstractChannel): CompletableFuture<out TController>

    fun toStreamHandler(): StreamHandler<TController> = object : StreamHandler<TController> {
        override fun handleStream(stream: Stream): CompletableFuture<out TController> {
            return initChannel(stream)
        }
    }
}
