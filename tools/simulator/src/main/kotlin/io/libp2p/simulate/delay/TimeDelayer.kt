package io.libp2p.simulate.delay

import io.libp2p.simulate.MessageDelayer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class TimeDelayer(
    val executor: ScheduledExecutorService,
    val delaySupplier: () -> Duration
) : MessageDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> {
        val ret = CompletableFuture<Unit>()
        executor.schedule({
            ret.complete(null)
        }, delaySupplier().inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return ret
    }
}
