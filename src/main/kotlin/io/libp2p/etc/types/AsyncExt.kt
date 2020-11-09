package io.libp2p.etc.types

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

fun <C> CompletableFuture<C>.bind(result: CompletableFuture<out C>) {
    result.whenComplete { res, t ->
        if (t != null) {
            completeExceptionally(t)
        } else {
            complete(res)
        }
    }
}

fun <C> CompletableFuture<C>.forward(forwardTo: CompletableFuture<in C>) = forwardTo.bind(this)

/**
 * The same as [CompletableFuture.get] but unwraps [ExecutionException]
 */
fun <C> CompletableFuture<C>.getX(): C {
    try {
        return get()
    } catch (t: Exception) {
        when (t) {
            is ExecutionException -> throw t.cause!!
            else -> throw t
        }
    }
}

/**
 * The same as [CompletableFuture.get] but unwraps [ExecutionException]
 */
fun <C> CompletableFuture<C>.getX(timeoutSec: Double): C {
    try {
        return get((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
    } catch (t: Exception) {
        when (t) {
            is ExecutionException -> throw t.cause!!
            else -> throw t
        }
    }
}

fun <C> ExecutorService.submitAsync(func: () -> CompletableFuture<C>): CompletableFuture<C> =
    CompletableFuture.supplyAsync(Supplier { func() }, this).thenCompose { it }

fun <C> completedExceptionally(t: Throwable) = CompletableFuture<C>().also { it.completeExceptionally(t) }

class NonCompleteException(cause: Throwable?) : RuntimeException(cause)
class NothingToCompleteException() : RuntimeException()

fun <C> anyComplete(all: List<CompletableFuture<C>>): CompletableFuture<C> =
    anyComplete(*all.toTypedArray())

fun <C> anyComplete(vararg all: CompletableFuture<C>): CompletableFuture<C> {
    return if (all.isEmpty()) completedExceptionally(NothingToCompleteException())
    else object : CompletableFuture<C>() {
        init {
            val counter = AtomicInteger(all.size)
            all.forEach {
                it.whenComplete { v, t ->
                    if (t == null) {
                        complete(v)
                    } else if (counter.decrementAndGet() == 0) {
                        completeExceptionally(NonCompleteException(t))
                    }
                }
            }
        }
    }
}

fun <C, R> Collection<CompletableFuture<C>>.thenApplyAll(func: (List<C>) -> R): CompletableFuture<R> =
    CompletableFuture.allOf(*this.toTypedArray())
        .thenApply { func(this.map { it.get() }) }
