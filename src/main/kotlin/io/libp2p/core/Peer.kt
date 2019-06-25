package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import java.util.concurrent.Future

abstract class Peer(private val host: Host, val id: PeerId) {
    open fun status(): Status = Status.KNOWN
    open fun addrs(): List<Multiaddr> = emptyList()

    abstract fun streams(): List<Stream>

    fun connect() : Future<Connection> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun connection() : Connection? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    enum class Status {
        KNOWN,
        CONNECTED
    }
}

