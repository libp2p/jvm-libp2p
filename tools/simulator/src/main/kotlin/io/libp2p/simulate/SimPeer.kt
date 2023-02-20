package io.libp2p.simulate

import io.libp2p.core.PeerId
import java.util.concurrent.CompletableFuture

interface SimPeer {

    val name: String
    val peerId: PeerId
    val connections: List<SimConnection>

    var inboundBandwidth: BandwidthDelayer
    var outboundBandwidth: BandwidthDelayer

    fun start() = CompletableFuture.completedFuture(Unit)

    fun connect(other: SimPeer): CompletableFuture<SimConnection>

    fun stop(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}
