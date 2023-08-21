package io.libp2p.simulate

import java.util.concurrent.CompletableFuture

fun interface MessageDelayer {
    fun delay(size: Long): CompletableFuture<Unit>

    companion object {
        val NO_DELAYER = MessageDelayer { CompletableFuture.completedFuture(null) }
    }
}
