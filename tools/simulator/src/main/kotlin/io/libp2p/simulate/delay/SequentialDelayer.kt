package io.libp2p.simulate.delay

import io.libp2p.simulate.MessageDelayer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class SequentialDelayer(
    val delegate: MessageDelayer,
    val executor: ScheduledExecutorService,
) : MessageDelayer {

    private var lastMessageFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(null)

    override fun delay(size: Long): CompletableFuture<Unit> {
        lastMessageFuture = lastMessageFuture.thenComposeAsync({
            delegate.delay(size)
        }, executor)
        return lastMessageFuture
    }

    companion object {
        fun MessageDelayer.sequential(executor: ScheduledExecutorService) =
            SequentialDelayer(this, executor)
    }
}
