package io.libp2p.core

import io.libp2p.etc.BroadcastStreamHandler
import java.util.concurrent.CompletableFuture

data class StreamPromise<T>(
    val stream: CompletableFuture<Stream> = CompletableFuture(),
    val controler: CompletableFuture<T> = CompletableFuture()
)

interface StreamHandler<out TController> {

    fun handleStream(stream: Stream): CompletableFuture<out TController>

    companion object {

        fun create(fn: (Stream) -> Unit) = object : StreamHandler<Unit> {
            override fun handleStream(stream: Stream): CompletableFuture<out Unit> {
                fn(stream)
                return CompletableFuture.completedFuture(Unit)
            }
        }

        fun <T> create(channelHandler: P2PAbstractHandler<T>) = object : StreamHandler<T> {
            override fun handleStream(stream: Stream): CompletableFuture<out T> {
                return channelHandler.initChannel(stream)
            }
        }

        fun createBroadcast(vararg handlers: StreamHandler<*>) =
            BroadcastStreamHandler().also { it += handlers }
    }

    interface Broadcast : StreamHandler<Any>, MutableList<StreamHandler<*>>
}
