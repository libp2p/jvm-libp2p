package io.libp2p.core

import java.util.concurrent.CompletableFuture

interface P2PAbstractHandler<out TController> {
    fun initChannel(ch: P2PAbstractChannel): CompletableFuture<out TController>

    fun toStreamHandler(): StreamHandler<TController> = object : StreamHandler<TController> {
        override fun handleStream(stream: Stream): CompletableFuture<out TController> {
            return initChannel(stream)
        }
    }
}
