package io.libp2p.core.types

import java.util.concurrent.CompletableFuture

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
