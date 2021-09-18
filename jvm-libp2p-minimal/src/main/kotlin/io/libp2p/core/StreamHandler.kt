package io.libp2p.core

import java.util.concurrent.CompletableFuture

/**
 * The pair of [Futures] as a result of initiating a [Stream]
 *
 * @property stream Is completed when a [Stream] instance was successfully created
 *          this property is used for low level Stream manipulations (like closing it)
 *
 * @property controller Is completed when the underlying client protocol is initiated.
 *           When the [stream] future is failed this future is also failed
 *           While the [stream] can be created successfully the protocol may fail
 *           to instantiateand this future would fail
 */
data class StreamPromise<T>(
    val stream: CompletableFuture<Stream> = CompletableFuture(),
    val controller: CompletableFuture<T> = CompletableFuture()
)

/**
 * The same as [P2PChannelHandler] with the [Stream] specialized [P2PChannel]
 */
fun interface StreamHandler<TController> {

    fun handleStream(stream: Stream): CompletableFuture<TController>
}
