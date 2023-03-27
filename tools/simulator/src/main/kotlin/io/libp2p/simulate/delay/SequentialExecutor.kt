package io.libp2p.simulate.delay

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class SequentialExecutor(
    val executor: ScheduledExecutorService,
) {
    private var lastMessageFuture: CompletableFuture<*> = CompletableFuture.completedFuture(Unit)

    fun <T : Any> enqueue(task: () -> CompletableFuture<T>): CompletableFuture<T> {
        val ret = lastMessageFuture.thenComposeAsync({
            task()
        }, executor)
        lastMessageFuture = ret
        return ret
    }
}
