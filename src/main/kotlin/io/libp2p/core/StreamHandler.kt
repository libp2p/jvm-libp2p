package io.libp2p.core

import io.libp2p.etc.BroadcastStreamHandler
import java.util.concurrent.CompletableFuture

/**
 * The pair of [Futures] as a result of initiating a [Stream]
 *
 * @property stream Is completed when a [Stream] instance was successfully created
 *          this property is used for low level Stream manipulations (like closing it)
 *
 * @property controler Is completed when the underlying client protocol is initiated.
 *           When the [stream] future is failed this future is also failed
 *           While the [stream] can be created successfully the protocol may fail
 *           to instantiateand this future would fail
 */
data class StreamPromise<T>(
    val stream: CompletableFuture<Stream> = CompletableFuture(),
    val controler: CompletableFuture<T> = CompletableFuture()
)

/**
 * The same as [P2PAbstractHandler] with the [Stream] specialized [P2PAbstractChannel]
 */
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
