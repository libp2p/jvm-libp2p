package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.netty.channel.ChannelFuture
import java.util.concurrent.Future

class Peer(val id: PeerId) {

    fun status(): Status = Status.KNOWN

    fun addrs(): List<Multiaddr> = emptyList()

    fun streams(): PeerStreams = PeerStreams()

    fun connect() : Future<PeerConnection> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun connection() : PeerConnection? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    enum class Status {
        KNOWN,
        CONNECTED
    }
}

