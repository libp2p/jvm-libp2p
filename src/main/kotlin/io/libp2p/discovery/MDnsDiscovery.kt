package io.libp2p.discovery

import io.libp2p.core.Host
import io.libp2p.core.PeerInfo
import io.libp2p.core.multiformats.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import javax.jmdns.AnswerListener
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.DNSRecord

typealias PeerListener = (PeerInfo) -> Unit

class MDnsDiscovery(private val host: Host) {
    private var mDns = JmDNS.create(InetAddress.getLocalHost())

    fun start(): CompletableFuture<Void> {
        mDns.registerService(
            ipfsDiscoveryInfo()
        )
        mDns.addAnswerListener(
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
            host.peerId.toBase58(),
            ip4Addresses()
        )
    }

    private fun listenPort(): Int {
        val address = host.listenAddresses().find {
            it.has(Protocol.IP4)
        }
        val str = address?.getStringComponent(Protocol.TCP)!!
        return Integer.parseInt(str)
    }

    private fun ip4Addresses(): List<Inet4Address> {
        return host.listenAddresses().map {
            it.getComponent(Protocol.IP4)
        }.filter {
            it != null
        }.map {
            InetAddress.getByAddress(InetAddress.getLocalHost().hostName, it)
        }.filterIsInstance(Inet4Address::class.java)
    }

    companion object {
        val ServiceTag = "_ipfs-discovery._udp"
        val ServiceTagLocal = "$ServiceTag.local."

        internal class Listener(
            private val parent: MDnsDiscovery
        ) : AnswerListener {
            override fun answersReceived(answers: List<DNSRecord>) {
                println(answers.last().toString())
            }
        }
    }
}