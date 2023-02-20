package io.libp2p.simulate.delay

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.util.isOrdered
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AccurateBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService,
    val timeSupplier: CurrentTimeSupplier,
    val name: String = "",
    val immediateExecutionBandwidth: Bandwidth = totalBandwidth / 10,
    val forceRemoveDeliveredMessagesOlderThan: Duration = 10.seconds
) : BandwidthDelayer {

    private val transferringMessages = mutableListOf<MessageData>()

    override fun delay(size: Long): CompletableFuture<Unit> {
        return if (immediateExecutionBandwidth.getTransmitTimeMillis(size) <= 1) {
            CompletableFuture.completedFuture(Unit)
        } else {
            prune()
            val msg = MessageData(Message(size, sendTime = timeSupplier()))
            transferringMessages += msg
            updateDeliverTimes(totalBandwidth, transferringMessages)
            val fut = CompletableFuture<Unit>()
            scheduleMessageWakeup(msg, fut)
            fut
        }
    }

    private fun scheduleMessageWakeup(msg: MessageData, completion: CompletableFuture<Unit>) {
        val delay = msg.deliverTime - timeSupplier()
        executor.schedule({
            if (timeSupplier() >= msg.deliverTime) {
                completion.complete(null)
                msg.delivered = true
                prune()
            } else {
                scheduleMessageWakeup(msg, completion)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun prune() {
        val curT = timeSupplier()
        transferringMessages.removeIf {
            it.delivered && (curT - it.deliverTime) > forceRemoveDeliveredMessagesOlderThan.inWholeMilliseconds
        }
        pruneIfAllDelivered()
    }

    private fun pruneIfAllDelivered() {
        if (transferringMessages.all { it.delivered }) {
            transferringMessages.clear()
        }
    }

    data class Message(
        val size: Long,
        val sendTime: Long
    )

    data class MessageData(
        val msg: Message,
        var deliverTime: Long = 0,
        var delivered: Boolean = false
    )

    companion object {

        fun updateDeliverTimes(totalBandwidth: Bandwidth, msgs: List<MessageData>) {
            val deliverTimes = calcDeliverTimes(totalBandwidth, msgs.map { it.msg })
            for (i in deliverTimes.indices) {
                msgs[i].deliverTime = deliverTimes[i]
            }
        }

        fun calcDeliverTimes(totalBandwidth: Bandwidth, msgs: List<Message>): LongArray {
            if (msgs.isEmpty()) return LongArray(0)
            if (msgs.size == 1) {
                val msg = msgs[0]
                val deliverTime = msg.sendTime + totalBandwidth.getTransmitTimeMillis(msg.size)
                return longArrayOf(deliverTime)
            }
            assert(msgs.map { it.sendTime }.isOrdered())

            data class IntMessage(
                val msg: Message,
                var lastUpdateSizeLeft: Long = msg.size,
                var deliverTime: Long = 0,
                var delivered: Boolean = false
            )

            val iMsgs = msgs.map { IntMessage(it) }

            fun recalcMessages(endIdx: Int, prevTime: Long, curTime: Long, prevMsgCount: Int, curMsgCount: Int) {
                val prevBand = totalBandwidth / Integer.max(1, prevMsgCount)
                val curBand = totalBandwidth / Integer.max(1, curMsgCount)
                for (i in 0 until endIdx) {
                    val msg = iMsgs[i]
                    if (!msg.delivered && msg.lastUpdateSizeLeft > 0) {
                        if (msg.msg.sendTime < curTime) {
                            val transmittedTillNowBytes = prevBand.getTransmitSize(curTime - prevTime)
                            msg.lastUpdateSizeLeft = java.lang.Long.max(
                                0,
                                msg.lastUpdateSizeLeft - transmittedTillNowBytes
                            )
                            msg.deliverTime = curTime + curBand.getTransmitTimeMillis(msg.lastUpdateSizeLeft)
                        } else {
                            msg.deliverTime = curTime + java.lang.Long.max(
                                1,
                                curBand.getTransmitTimeMillis(msg.lastUpdateSizeLeft)
                            )
                        }
                    }
                }
            }

            var curTime = msgs[0].sendTime
            var prevTime: Long
            var curMsgCount = 0

            for (i in msgs.indices) {
                val msg = iMsgs[i]

                while (true) {
                    val nearestDeliveryMsg = iMsgs
                        .take(i)
                        .filter { !it.delivered && it.deliverTime < msg.msg.sendTime }
                        .minByOrNull { it.deliverTime }
                        ?: break

                    prevTime = curTime
                    curTime = nearestDeliveryMsg.deliverTime
                    curMsgCount--
                    assert(curMsgCount >= 0)

                    nearestDeliveryMsg.delivered = true
                    recalcMessages(i, prevTime, curTime, curMsgCount + 1, curMsgCount)
                }

                prevTime = curTime
                curTime = msg.msg.sendTime
                curMsgCount++

                recalcMessages(i + 1, prevTime, curTime, curMsgCount - 1, curMsgCount)
            }

            while (true) {
                val nearestDeliveryMsg = iMsgs
                    .filter { !it.delivered }
                    .minByOrNull { it.deliverTime }
                    ?: break

                prevTime = curTime
                curTime = nearestDeliveryMsg.deliverTime
                curMsgCount--
                assert(curMsgCount >= 0)

                nearestDeliveryMsg.delivered = true
                recalcMessages(msgs.size, prevTime, curTime, curMsgCount + 1, curMsgCount)
            }

            return iMsgs.map { it.deliverTime }.toLongArray()
        }
    }
}
