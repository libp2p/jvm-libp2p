package io.libp2p.pubsub.gossip

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.MILLISECONDS

open class Heartbeat {

    val listeners = CopyOnWriteArrayList<(Long) -> Unit>()

    fun fireBeat() {
        fireBeat(currentTime())
    }

    fun fireBeat(time: Long) {
        listeners.forEach { it(time) }
    }

    open fun currentTime() = System.currentTimeMillis()

    companion object {
        fun create(executor: ScheduledExecutorService, interval: Duration, curTime: () -> Long): Heartbeat {
            val heartbeat = object : Heartbeat() {
                override fun currentTime()= curTime()
            }
            executor.scheduleAtFixedRate(heartbeat::fireBeat, interval.toMillis(), interval.toMillis(), MILLISECONDS)
            return heartbeat
        }
    }
}