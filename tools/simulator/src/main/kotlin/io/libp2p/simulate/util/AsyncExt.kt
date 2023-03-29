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