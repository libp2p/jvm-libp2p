package io.libp2p.pubsub.gossip

import java.util.concurrent.CopyOnWriteArrayList

class Heartbeat {

    val listeners = CopyOnWriteArrayList<(Long) -> Unit>()

    fun fireBeat(time: Long) {
        listeners.forEach { it(time) }
    }

    fun currentTime() = System.currentTimeMillis()
}