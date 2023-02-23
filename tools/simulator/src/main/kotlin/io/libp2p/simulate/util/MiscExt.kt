package io.libp2p.tools

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private val LOG_TIME_FORMAT = SimpleDateFormat("hh:mm:ss.SSS")

fun log(s: String) {
    println("[${LOG_TIME_FORMAT.format(Date())}] $s")
}

fun ScheduledExecutorService.delayedFuture(delay: Duration): CompletableFuture<Unit> {
    val fut = CompletableFuture<Unit>()
    this.schedule({ fut.complete(null) }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return fut
}

fun <R> ScheduledExecutorService.schedule(delay: Duration, callable: () -> R): ScheduledFuture<R> =
    this.schedule(Callable { callable() }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
