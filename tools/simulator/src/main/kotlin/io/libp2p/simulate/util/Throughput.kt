package io.libp2p.simulate.util

import io.libp2p.simulate.RandomValue
import java.lang.Double.max
import java.util.concurrent.ScheduledExecutorService

class Throughput(
    val bytesPerSecond: RandomValue,
    val scheduler: ScheduledExecutorService,
    val msgSizeEstimator: (Any) -> Int,
    val timeSupplier: () -> Long
) {

    var lastMsgSchedTime = 0.0

    fun createDelayHandler(): DelayNettyOutboundHandler {
        return DelayNettyOutboundHandler(scheduler, msgSizeEstimator, this::calcDelay)
    }

    fun calcDelay(msgSize: Int): Long {
        val msgMillis = msgSize * 1000 / bytesPerSecond.next()
        val curTime = timeSupplier().toDouble()
        lastMsgSchedTime = max(curTime, lastMsgSchedTime) + msgMillis
        return (lastMsgSchedTime - curTime).toLong()
    }
}
