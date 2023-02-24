package io.libp2p.simulate

import io.libp2p.core.PeerId
import java.util.concurrent.CompletableFuture

typealias SimPeerId = Int

interface SimPeer {

    val simPeerId: SimPeerId
    val name: String get() = simPeerId.toString()
    val peerId: PeerId
    val connections: List<SimConnection>

    var inboundBandwidth: BandwidthDelayer
    var outboundBandwidth: BandwidthDelayer

    fun start() = CompletableFuture.completedFuture(Unit)

    fun connect(other: SimPeer): CompletableFuture<SimConnection>

    fun stop(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}
