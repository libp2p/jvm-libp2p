package io.libp2p.simulate.delay

import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.delay.SequentialDelayer.Companion.sequential
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class ChannelMessageDelayer(
    executor: ScheduledExecutorService,
    localOutboundBandwidthDelayer: BandwidthDelayer,
    connectionLatencyDelayer: MessageDelayer,
    remoteInboundBandwidthDelayer: BandwidthDelayer,
) : MessageDelayer {

    private val sequentialOutboundBandwidthDelayer = localOutboundBandwidthDelayer.sequential(executor)
    private val sequentialInboundBandwidthDelayer = remoteInboundBandwidthDelayer.sequential(executor)

    private val delayer = MessageDelayer { size ->
        CompletableFuture.allOf(
            sequentialOutboundBandwidthDelayer.delay(size)
                .thenCompose { connectionLatencyDelayer.delay(size) },
            connectionLatencyDelayer.delay(size)
                .thenCompose { sequentialInboundBandwidthDelayer.delay(size) }
        ).thenApply { }
    }

    override fun delay(size: Long): CompletableFuture<Unit> = delayer.delay(size)
}
