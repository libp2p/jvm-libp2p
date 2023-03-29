package io.libp2p.simulate.delay

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.DelayDetails
import io.libp2p.simulate.MessageDelayer
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
    val orderPreservingExecutor = OrderPreservingExecutor()

    fun delay(msgSize: Long): CompletableFuture<DelayDetails> {
        val originateTime = currentTime()

        data class BandAndLatencyTimes(
            val beforeOutboundTime: Long,
            val outboundBandwidthDelay: Long,
            val inboundBandwidthDelay: Long,

            val afterBandwidthTime: Long,
            val afterLatencyTime: Long = 0,
            val afterInboundTime: Long = 0,
        )

        val afterBandwidthFut = sequentialExecutor.enqueue {
            val beforeOutboundTime = currentTime()
            val outDelay = outboundBandwidthDelayer.delay(msgSize)
                .thenApply {
                    currentTime()
                }
            val inDelay = inboundBandwidthDelayer.delay(msgSize)
                .thenApply {
                    currentTime()
                }

            // the time of the slowest inbound/outbound bandwidth delay is taken
            outDelay.thenCombine(inDelay) { outb, inb ->
                BandAndLatencyTimes(
                    beforeOutboundTime,
                    outb - beforeOutboundTime,
                    inb - beforeOutboundTime,
                    currentTime()
                )
            }
        }

        val afterLatencyFut = afterBandwidthFut.thenCompose { tt ->
            connectionLatencyDelayer.delay(msgSize)
                .thenApply {
                    tt.copy(afterLatencyTime = currentTime())
                }
        }

        return orderPreservingExecutor.enqueue(afterLatencyFut)
            .thenApply {
                DelayDetails(
                    it.beforeOutboundTime - originateTime,
                    it.outboundBandwidthDelay,
                    it.afterLatencyTime - it.afterBandwidthTime,
                    it.inboundBandwidthDelay,
                    currentTime() - it.afterLatencyTime
                )
            }
    }
}