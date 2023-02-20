package io.libp2p.simulate.wire

import io.libp2p.core.Host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.simulate.AbstractSimPeer
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class WireSimPeer(val host: Host) : AbstractSimPeer() {

    private val port = portCounter.getAndIncrement()

    override val connections: MutableList<SimConnection> = Collections.synchronizedList(ArrayList())

    override fun start() = host.network.listen(getMultiaddr())

    override fun connectImpl(other: SimPeer): CompletableFuture<SimConnection> {
        other as WireSimPeer
        return host.network.connect(other.host.peerId, other.getMultiaddr())
            .thenApply { WireSimConnection(this, other, it) }
    }

    override fun stop() = host.stop().thenApply { }

    override val peerId = host.peerId

    override var inboundBandwidth: BandwidthDelayer
        get() = TODO("Not yet implemented")
        set(_) {}
    override var outboundBandwidth: BandwidthDelayer
        get() = TODO("Not yet implemented")
        set(_) {}

    fun getMultiaddr() = Multiaddr("/ip/127.0.0.1/tcp/$port")

    companion object {
        private val portCounter = AtomicInteger(10000)
    }
}
