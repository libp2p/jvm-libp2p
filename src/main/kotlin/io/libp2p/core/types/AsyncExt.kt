package io.libp2p.core.types

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

fun <C> CompletableFuture<C>.bind(result: CompletableFuture<C>) {
    result.whenComplete { res, t ->
        if (t != null) {
            completeExceptionally(t)
        } else {
            complete(res)
        }
    }
}

fun <C> CompletableFuture<C>.forward(forwardTo: CompletableFuture<C>) = forwardTo.bind(this)

fun <C> ExecutorService.submitAsync(func: () -> CompletableFuture<C>): CompletableFuture<C> =
    CompletableFuture.supplyAsync(Supplier { func() }, this).thenCompose { it }

fun <C> completedExceptionally(t: Throwable) = CompletableFuture<C>().also { it.completeExceptionally(t) }

class NonCompleteException(cause: Throwable?) : RuntimeException(cause)
class NothingToCompleteException() : RuntimeException()

fun <C> anyComplete(all: List<CompletableFuture<C>>): CompletableFuture<C> = anyComplete(*all.toTypedArray())

fun <C> anyComplete(vararg all: CompletableFuture<C>): CompletableFuture<C> {
    return if (all.isEmpty()) CompletableFuture<C>().also { it.completeExceptionally(NothingToCompleteException()) }
    else object : CompletableFuture<C>() {
        init {
            all.forEach { it.whenComplete { v, t ->
                if (t == null) {
                    complete(v)
                } else if (counter.decrementAndGet() == 0) {
                    completeExceptionally(NonCompleteException(t))
                }
            } }
        }
        val counter = AtomicInteger(all.size)
    }
}