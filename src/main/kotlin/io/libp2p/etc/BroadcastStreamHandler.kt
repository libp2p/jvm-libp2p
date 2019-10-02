package io.libp2p.etc

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class BroadcastStreamHandler(
    private val handlers: MutableList<StreamHandler<*>> = CopyOnWriteArrayList()
) : StreamHandler.Broadcast, MutableList<StreamHandler<*>> by handlers {
    override fun handleStream(stream: Stream): CompletableFuture<out Any> {
        handlers.forEach {
            it.handleStream(stream)
        }
        return CompletableFuture.completedFuture(Any())
    }
}