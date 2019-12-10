package io.libp2p.discovery

import io.libp2p.core.Host
import io.libp2p.core.PeerInfo
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

typealias PeerListener = (PeerInfo) -> Unit

class Listener : ServiceListener {
    override fun serviceResolved(event: ServiceEvent?) {
    }

    override fun serviceRemoved(event: ServiceEvent?) {
    }

    override fun serviceAdded(event: ServiceEvent) {
        println("Added")
        println(event.toString())
        println(event.info.toString())
    }

}

class MDnsDiscovery(
    private val host: Host
) {
    private var mDns = JmDNS.create(InetAddress.getLocalHost())

    fun start(): CompletableFuture<Void> {
        mDns.registerService(
            ipfsDiscoveryInfo()
        )
        mDns.addServiceListener(
            ServiceTagLocal,
            Listener()
        )

        return CompletableFuture.completedFuture(null)
    }

    fun stop(): CompletableFuture<Void> {
        mDns.unregisterAllServices()

        return CompletableFuture.completedFuture(null)
    }

    fun onPeerFound(listener: PeerListener) { }

    private fun ipfsDiscoveryInfo(): ServiceInfo {
        return ServiceInfo.create(
            ServiceTagLocal,
            host.peerId.toBase58(),
            1234,
            "hello mother"
        )
    }

    companion object {
        val ServiceTag = "_ipfs-discovery._udp"
        val ServiceTagLocal = "${ServiceTag}.local."
    }
}