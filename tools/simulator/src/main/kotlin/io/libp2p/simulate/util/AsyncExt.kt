package io.libp2p.simulate.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

fun <T> ScheduledExecutorService.scheduleCompletable(delay: Duration, task: () -> T): CompletableFuture<T> {
    val ret = CompletableFuture<T>()
    this.schedule({
        try {
            val v = task()
            ret.complete(v)
        } catch (e: Exception) {
            ret.completeExceptionally(e)
        }
    }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return ret
}

fun <T> ScheduledExecutorService.delay(delay: Duration, task: () -> T) = scheduleCompletable(delay, task)

fun ScheduledExecutorService.scheduleAtFixedRate(
    period: Duration,
    initialDuration: Duration = Duration.ZERO,
    task: () -> Unit
) {
    this.scheduleAtFixedRate(
        task,
        initialDuration.inWholeMilliseconds,
        period.inWholeMilliseconds,
        TimeUnit.MILLISECONDS
    )
}
