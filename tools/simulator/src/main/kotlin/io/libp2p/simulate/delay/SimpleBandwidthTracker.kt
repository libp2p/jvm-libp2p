package io.libp2p.simulate.delay

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.tools.delayedFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class SimpleBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : BandwidthDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> =
        executor.delayedFuture(totalBandwidth.getTransmitTime(size))
}
