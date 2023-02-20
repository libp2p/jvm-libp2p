package io.libp2p.simulate.delay

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.tools.delayedFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class SequentialBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : BandwidthDelayer {

    private var lastMessageFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(null)

    override fun delay(size: Long): CompletableFuture<Unit> {
        lastMessageFuture = lastMessageFuture.thenCompose {
            executor.delayedFuture(totalBandwidth.getTransmitTime(size))
        }
        return lastMessageFuture
    }
}
