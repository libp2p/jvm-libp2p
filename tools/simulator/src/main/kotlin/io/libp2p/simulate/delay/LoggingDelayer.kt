package io.libp2p.simulate.delay

import io.libp2p.simulate.BandwidthDelayer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class LoggingDelayer(
    val delegate: BandwidthDelayer,
    val logger: (String) -> Unit
) : BandwidthDelayer {

    val counter = AtomicInteger(0)

    override val totalBandwidth = delegate.totalBandwidth

    override fun delay(size: Long): CompletableFuture<Unit> {
        val id = counter.getAndIncrement()
        logger("[$id] Started $size")
        return delegate.delay(size)
            .thenApply {
                logger("[$id] Completed $size")
            }
    }

    companion object {
        fun BandwidthDelayer.logging(logger: (String) -> Unit) = LoggingDelayer(this, logger)
    }
}
