package io.libp2p.simulate.connection

import io.libp2p.core.Host
import io.libp2p.simulate.AbstractSimPeer
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer
import java.util.concurrent.CompletableFuture

class HostSimPeer(val host: Host) : AbstractSimPeer() {

    override fun connectImpl(other: SimPeer): CompletableFuture<SimConnection> {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    val transport by lazy { (host.network.transports[0] as LoopbackTransport) }
    val ip by lazy { transport.localIp }
    override val peerId = host.peerId

    override var inboundBandwidth: BandwidthDelayer
        get() = TODO("Not yet implemented")
        set(_) {}
    override var outboundBandwidth: BandwidthDelayer
        get() = TODO("Not yet implemented")
        set(_) {}

    override fun start() = host.start().thenApply { }
    override fun stop() = host.stop().thenApply { }
}
