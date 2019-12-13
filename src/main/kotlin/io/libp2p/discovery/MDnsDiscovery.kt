package io.libp2p.discovery

import io.libp2p.core.*
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import io.libp2p.discovery.mdns.AnswerListener
import io.libp2p.discovery.mdns.JmDNS
import io.libp2p.discovery.mdns.ServiceInfo
import io.libp2p.discovery.mdns.impl.DNSRecord
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType

class MDnsDiscovery(
    private val host: Host,
    private val serviceTag: String = ServiceTagLocal,
    private val queryInterval: Int = QueryInterval
) : Discoverer {
    private var mDns = JmDNS.create(InetAddress.getLocalHost())
    private val listeners = mutableListOf<PeerListener>()

    override fun start(): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            mDns.start()

            mDns.registerService(
                ipfsDiscoveryInfo()
            )
            mDns.addAnswerListener(
                serviceTag,
                queryInterval,
                Listener(this)
            )
        }
    }

    override fun stop(): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            mDns.stop()
        }
    }

    override fun onPeerFound(listener: PeerListener) {
        listeners += listener
    }

    internal fun peerFound(peerInfo: PeerInfo) {
        listeners.forEach { it(peerInfo) }
    }

    private fun ipfsDiscoveryInfo(): ServiceInfo {
        return ServiceInfo.create(
            serviceTag,
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
        val QueryInterval = 120

        internal class Listener(
            private val parent: MDnsDiscovery
        ) : AnswerListener {
            override fun answersReceived(answers: List<DNSRecord>) {
                val txtRecord = answers.find { DNSRecordType.TYPE_TXT.equals(it.recordType) } as DNSRecord.Text
                val srvRecord = answers.find { DNSRecordType.TYPE_SRV.equals(it.recordType) } as DNSRecord.Service
                val aRecords = answers.filter { DNSRecordType.TYPE_A.equals(it.recordType) }

                var peerIdStr = String(txtRecord?.text)
                if (peerIdStr[0] == '.') peerIdStr = peerIdStr.substring(1)
                val peerId = PeerId.fromBase58(peerIdStr)
                val port = srvRecord.port

                val multiAddrs = aRecords.map {
                    it as DNSRecord.IPv4Address
                    "/ip4/${it.address.hostAddress}/tcp/$port"
                }.map {
                    Multiaddr(it)
                }

                val peerInfo = PeerInfo(peerId, multiAddrs)
                parent.peerFound(peerInfo)
            }
        }
    }
}