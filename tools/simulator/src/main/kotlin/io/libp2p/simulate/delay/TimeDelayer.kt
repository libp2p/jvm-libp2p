package io.libp2p.simulate.delay

import io.libp2p.simulate.MessageDelayer
import io.libp2p.tools.schedule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import kotlin.time.Duration

class TimeDelayer(
    val executor: ScheduledExecutorService,
    val delaySupplier: () -> Duration
) : MessageDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> {
        val ret = CompletableFuture<Unit>()
        executor.schedule(delaySupplier()) {
            ret.complete(null)
        }
        return ret
    }
}
