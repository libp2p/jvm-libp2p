package io.libp2p.tools

import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private val LOG_TIME_FORMAT = SimpleDateFormat("hh:mm:ss.SSS")

fun log(s: String) {
    println("[${LOG_TIME_FORMAT.format(Date())}] $s")
}

fun ScheduledExecutorService.delayedFuture(delay: kotlin.time.Duration): CompletableFuture<Unit> {
    val fut = CompletableFuture<Unit>()
    this.schedule({ fut.complete(null) }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return fut
}
