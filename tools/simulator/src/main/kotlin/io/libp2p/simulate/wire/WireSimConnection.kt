package io.libp2p.simulate.wire

import io.libp2p.core.Connection
import io.libp2p.simulate.*

class WireSimConnection(
    override val dialer: SimPeer,
    override val listener: SimPeer,
    val conn: Connection
) : SimConnection {

    override val closed = conn.closeFuture()

    override fun close() {
        conn.close()
    }

    override val streams: List<SimStream>
        get() = TODO("Not yet implemented")
    override var connectionLatency: MessageDelayer
        get() = TODO("Not yet implemented")
        set(_) {}
}
