package io.libp2p.simulate

import java.util.concurrent.CompletableFuture

interface SimConnection {

    val dialer: SimPeer
    val listener: SimPeer

    val streams: List<SimStream>

    val closed: CompletableFuture<Unit>

    var connectionLatency: MessageDelayer

    fun close()
}
