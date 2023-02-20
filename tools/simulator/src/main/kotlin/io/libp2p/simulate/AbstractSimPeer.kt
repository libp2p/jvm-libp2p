package io.libp2p.simulate

import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList

abstract class AbstractSimPeer : SimPeer {

    override val name = counter.getAndIncrement().toString()

    override val connections: MutableList<SimConnection> = Collections.synchronizedList(ArrayList())

    override fun connect(other: SimPeer): CompletableFuture<SimConnection> {
        return connectImpl(other).thenApply { conn ->
            val otherAbs = other as? AbstractSimPeer
            connections += conn
            otherAbs?.connections?.add(conn)
            conn.closed.thenAccept {
                connections -= conn
                otherAbs?.connections?.remove(conn)
            }
            conn
        }
    }

    abstract fun connectImpl(other: SimPeer): CompletableFuture<SimConnection>

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AbstractSimPeer
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    companion object {
        val counter = AtomicInteger()
    }
}
