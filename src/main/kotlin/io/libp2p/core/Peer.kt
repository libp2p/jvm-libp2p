package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import java.util.concurrent.Future

abstract class Peer(private val host: HostImpl, val id: PeerId) {
    open fun status(): Status = Status.KNOWN
    open fun addrs(): List<Multiaddr> = emptyList()

    abstract fun streams(): List<Stream>

    fun connect(): Future<Connection> {
        TODO("not implemented")
    }

    fun disconnect() {
        TODO("not implemented")
    }

    fun connection(): Connection? {
        TODO("not implemented")
    }

    enum class Status {
        KNOWN,
        CONNECTED
    }
}
