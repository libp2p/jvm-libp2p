package io.libp2p.discovery

import io.libp2p.core.Host
import io.libp2p.core.PeerInfo
import io.libp2p.core.multiformats.Protocol
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

typealias PeerListener = (PeerInfo) -> Unit

class MDnsDiscovery(private val host: Host) {
    private var mDns = JmDNS.create(InetAddress.getLocalHost())

    fun start(): CompletableFuture<Void> {
        mDns.registerService(
            ipfsDiscoveryInfo()
        )
        mDns.addServiceListener(
            ServiceTagLocal,
            Listener(this)
        )

        return CompletableFuture.completedFuture(null)
    }

    fun stop(): CompletableFuture<Void> {
        mDns.unregisterAllServices()
        mDns.close()

        return CompletableFuture.completedFuture(null)
    }

    fun onPeerFound(listener: PeerListener) { }

    private fun ipfsDiscoveryInfo(): ServiceInfo {
        return ServiceInfo.create(
            ServiceTagLocal,
            host.peerId.toBase58(),
            listenPort(),
            host.peerId.toBase58()
        )
    }

    private fun listenPort(): Int {
        val address = host.listenAddresses().find {
            it.has(Protocol.IP4)
        }
        val str = address?.getStringComponent(Protocol.TCP)!!
        return Integer.parseInt(str)
    }

    companion object {
        val ServiceTag = "_ipfs-discovery._udp"
        val ServiceTagLocal = "${ServiceTag}.local."

        internal class Listener(
            private val parent: MDnsDiscovery
        ) : ServiceListener {
            override fun serviceResolved(event: ServiceEvent) = service(event)
            override fun serviceRemoved(event: ServiceEvent) = service(event)
            override fun serviceAdded(event: ServiceEvent) = service(event)

            fun service(event: ServiceEvent) {
                println(event.toString())
                println(event.info.toString())
            }
        }
    }

}