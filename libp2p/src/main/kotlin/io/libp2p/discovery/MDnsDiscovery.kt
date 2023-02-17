package io.libp2p.discovery

import io.libp2p.core.Discoverer
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.PeerListener
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.discovery.mdns.AnswerListener
import io.libp2p.discovery.mdns.JmDNS
import io.libp2p.discovery.mdns.ServiceInfo
import io.libp2p.discovery.mdns.impl.DNSRecord
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool

class MDnsDiscovery(
    private val host: Host,
    private val serviceTag: String = ServiceTagLocal,
    private val queryInterval: Int = QueryInterval,
    val address: InetAddress? = null
) : Discoverer {
    private val localhost = InetAddress.getLocalHost()
    private var mDns = JmDNS.create(address ?: localhost)
    override val newPeerFoundListeners: MutableCollection<PeerListener> = CopyOnWriteArrayList()
    private val executor by lazy { ForkJoinPool(1) }

    override fun start(): CompletableFuture<Void> {
        return CompletableFuture.runAsync(
            Runnable {
                mDns.start()

                mDns.registerService(
                    ipfsDiscoveryInfo()
                )
                mDns.addAnswerListener(
                    serviceTag,
                    queryInterval,
                    Listener(this)
                )
            },
            executor
        )
    }

    override fun stop(): CompletableFuture<Void> {
        return CompletableFuture.runAsync(
            Runnable {
                mDns.stop()
            },
            executor
        )
    }

    internal fun peerFound(peerInfo: PeerInfo) {
        newPeerFoundListeners.forEach { it(peerInfo) }
    }

    private fun ipfsDiscoveryInfo(): ServiceInfo {
        return ServiceInfo.create(
            serviceTag,
            host.peerId.toBase58(),
            listenPort(),
            host.peerId.toBase58(),
            ip4Addresses(),
            ip6Addresses()
        )
    }

    private fun listenPort(): Int {
        val address = host.listenAddresses().find {
            it.has(Protocol.IP4)
        }
        val str = address?.getFirstComponent(Protocol.TCP)?.stringValue!!
        return Integer.parseInt(str)
    }

    private fun ip4Addresses() = ipAddresses(Protocol.IP4, Inet4Address::class.java)
    private fun ip6Addresses() = ipAddresses(Protocol.IP6, Inet6Address::class.java)

    private fun <R> ipAddresses(protocol: Protocol, klass: Class<R>): List<R> {
        return host.listenAddresses().map {
            it.getFirstComponent(protocol)
        }.filterNotNull().map {
            InetAddress.getByAddress(localhost.hostName, it.value)
        }.filterIsInstance(klass)
    }

    companion object {
        val ServiceTag = "_ipfs-discovery._udp"
        val ServiceTagLocal = "$ServiceTag.local."
        val QueryInterval = 120

        internal class Listener(
            private val parent: MDnsDiscovery
        ) : AnswerListener {
            override fun answersReceived(answers: List<DNSRecord>) {
                val txtRecord = answers.find { DNSRecordType.TYPE_TXT.equals(it.recordType) }
                val srvRecord = answers.find { DNSRecordType.TYPE_SRV.equals(it.recordType) }
                val aRecords = answers.filter { DNSRecordType.TYPE_A.equals(it.recordType) }
                val aaaaRecords = answers.filter { DNSRecordType.TYPE_AAAA.equals(it.recordType) }

                if (txtRecord == null || srvRecord == null || aRecords.isEmpty())
                    return // incomplete answers

                txtRecord as DNSRecord.Text
                srvRecord as DNSRecord.Service

                var peerIdStr = String(txtRecord.text)
                if (peerIdStr[0] == '.') peerIdStr = peerIdStr.substring(1)
                val peerId = PeerId.fromBase58(peerIdStr)
                val port = srvRecord.port

                val ip4multiAddrStr = aRecords.map {
                    it as DNSRecord.IPv4Address
                    "/ip4/${it.address.hostAddress}/tcp/$port"
                }
                val ip6multiAddrStr = aaaaRecords.map {
                    it as DNSRecord.IPv6Address
                    "/ip6/${it.address.hostAddress}/tcp/$port"
                }

                val multiAddrs = (ip4multiAddrStr + ip6multiAddrStr).map { Multiaddr(it) }

                val peerInfo = PeerInfo(peerId, multiAddrs)
                parent.peerFound(peerInfo)
            }
        }
    }
}
