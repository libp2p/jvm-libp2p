package io.libp2p.discovery

import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import javax.jmdns.JmDNS

typealias PeerListener = (PeerInfo) -> Unit

class MDnsDiscovery(
    private val peerId: PeerId
) {
    private var mDns: JmDNS? = null

    fun start(): CompletableFuture<Void> {
        mDns = JmDNS.create(InetAddress.getLocalHost())

        return CompletableFuture.completedFuture(null)
    }

    fun stop(): CompletableFuture<Void> {
        mDns?.unregisterAllServices()

        return CompletableFuture.completedFuture(null)
    }

    fun onPeerFound(listener: PeerListener) { }
}