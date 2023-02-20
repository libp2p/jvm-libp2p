package io.libp2p.simulate.util

import io.libp2p.simulate.RandomValue
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

open class DelayNettyOutboundHandler(
    val scheduler: ScheduledExecutorService,
    val msgSizeEstimator: (Any) -> Int,
    val msgDelayCalc: (Int) -> Long
) : ChannelOutboundHandlerAdapter() {

    override fun write(ctx: ChannelHandlerContext?, msg: Any, promise: ChannelPromise) {
        val msgSize = msgSizeEstimator(msg)
        val msgDelay = msgDelayCalc(msgSize)
        if (msgDelay == 0L) {
            super.write(ctx, msg, promise)
        } else {
            scheduler.schedule({
                super.write(ctx, msg, promise)
                flush(ctx)
            }, msgDelay, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        fun simpleDelayed(scheduler: ScheduledExecutorService, delay: RandomValue) =
            DelayNettyOutboundHandler(scheduler, { 0 }, { delay.next().toLong() })
    }
}
