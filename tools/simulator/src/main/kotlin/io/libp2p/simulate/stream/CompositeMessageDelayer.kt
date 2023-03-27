package io.libp2p.simulate.stream

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.DelayData
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.delay.SequentialExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class CompositeMessageDelayer(
    val outboundBandwidthDelayer: BandwidthDelayer,
    val connectionLatencyDelayer: MessageDelayer,
    val inboundBandwidthDelayer: BandwidthDelayer,
    val executor: ScheduledExecutorService,
    val currentTime: CurrentTimeSupplier,
) {
    val sequentialExecutor = SequentialExecutor(executor)

    fun delay(msgSize: Long): CompletableFuture<DelayData> {
        val originateTime = currentTime()

        data class BandAndLatencyTimes(
            val latencyPassDelay: Long,
            val bandwidthPassDelay: Long
        ) {
            val totalDelay get() = latencyPassDelay + bandwidthPassDelay
        }

        return sequentialExecutor.enqueue {
            val wireTime = currentTime()

            val outboundFut =
                outboundBandwidthDelayer.delay(msgSize)
                    .thenCompose {
                        val outboundPassTime = currentTime()
                        connectionLatencyDelayer.delay(msgSize)
                            .thenApply {
                                val latencyPassTime = currentTime()
                                BandAndLatencyTimes(latencyPassTime - outboundPassTime, outboundPassTime - wireTime)
                            }
                    }
            val inboundFut =
                connectionLatencyDelayer.delay(msgSize)
                    .thenCompose {
                        val latencyPassTime = currentTime()
                        inboundBandwidthDelayer.delay(msgSize)
                            .thenApply {
                                val inboundPassTime = currentTime()
                                BandAndLatencyTimes(latencyPassTime - wireTime, inboundPassTime - latencyPassTime)
                            }
                    }

            outboundFut.thenCombine(inboundFut) { outbound, inbound ->
                val latencyDelay = if (outbound.totalDelay > inbound.totalDelay)
                    outbound.latencyPassDelay else inbound.latencyPassDelay
                DelayData(
                    wireTime - originateTime,
                    outbound.bandwidthPassDelay,
                    latencyDelay,
                    inbound.bandwidthPassDelay
                )
            }
        }
    }
}