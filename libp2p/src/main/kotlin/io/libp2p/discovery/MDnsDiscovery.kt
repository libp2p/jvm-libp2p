package io.libp2p.discovery

import io.libp2p.core.*
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrComponent
import io.libp2p.core.multiformats.Protocol
import io.libp2p.discovery.mdns.AnswerListener
import io.libp2p.discovery.mdns.JmDNS
import io.libp2p.discovery.mdns.ServiceInfo
import io.libp2p.discovery.mdns.impl.DNSRecord
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType
import java.net.*
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ForkJoinPool
import java.util.stream.Collectors
import java.util.stream.Stream

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

    fun addHandler(h: PeerListener) {
        newPeerFoundListeners += h
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
        val ipv6OnlyAddress = if (address == null) {
            host.listenAddresses().find {
                it.has(Protocol.IP6)
            }
        } else {
            address
        }
        val str = ipv6OnlyAddress?.getFirstComponent(Protocol.TCP)?.stringValue!!
        return Integer.parseInt(str)
    }

    /* /ip6/::/tcp/4001 should expand to the following for example:
    "/ip6/0:0:0:0:0:0:0:1/udp/4001/quic"
    "/ip4/50.116.48.246/tcp/4001"
    "/ip4/127.0.0.1/tcp/4001"
    "/ip6/2600:3c03:0:0:f03c:92ff:fee7:bc1c/tcp/4001"
    "/ip6/0:0:0:0:0:0:0:1/tcp/4001"
    "/ip4/50.116.48.246/udp/4001/quic"
    "/ip4/127.0.0.1/udp/4001/quic"
    "/ip6/2600:3c03:0:0:f03c:92ff:fee7:bc1c/udp/4001/quic"
     */
    fun expandWildcardAddresses(addr: Multiaddr): List<Multiaddr> {
        // Do not include /p2p or /ipfs components which are superfluous here
        if (!isWildcard(addr)) {
            return java.util.List.of(
                Multiaddr(
                    addr.components
                        .stream()
                        .filter { c: MultiaddrComponent ->
                            (
                                c.protocol !== Protocol.P2P &&
                                    c.protocol !== Protocol.IPFS
                                )
                        }
                        .collect(Collectors.toList())
                )
            )
        }
        if (addr.has(Protocol.IP4)) return listNetworkAddresses(false, addr)
        return if (addr.has(Protocol.IP6)) listNetworkAddresses(true, addr) else emptyList()
    }

    fun listNetworkAddresses(includeIp6: Boolean, addr: Multiaddr): List<Multiaddr> {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap { net: NetworkInterface ->
                    net.interfaceAddresses.stream()
                        .map { obj: InterfaceAddress -> obj.address }
                        .filter { ip: InetAddress? -> includeIp6 || ip is Inet4Address }
                }
                .map { ip: InetAddress ->
                    Multiaddr(
                        Stream.concat(
                            Stream.of(
                                MultiaddrComponent(
                                    if (ip is Inet4Address) Protocol.IP4 else Protocol.IP6,
                                    ip.address
                                )
                            ),
                            addr.components.stream()
                                .filter { c: MultiaddrComponent ->
                                    c.protocol !== Protocol.IP4 && c.protocol !== Protocol.IP6 && c.protocol !== Protocol.P2P && c.protocol !== Protocol.IPFS
                                }
                        )
                            .collect(Collectors.toList())
                    )
                }
                .collect(Collectors.toList())
        } catch (e: SocketException) {
            throw RuntimeException(e)
        }
    }

    fun isWildcard(addr: Multiaddr): Boolean {
        val s = addr.toString()
        return s.contains("/::/") || s.contains("/0:0:0:0/")
    }

    private fun ip4Addresses() = ipAddresses(Protocol.IP4, Inet4Address::class.java)
    private fun ip6Addresses() = ipAddresses(Protocol.IP6, Inet6Address::class.java)

    private fun <R> ipAddresses(protocol: Protocol, klass: Class<R>): List<R> {
        return host.listenAddresses().flatMap {
            expandWildcardAddresses(it)
        }.map {
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

                if (txtRecord == null || srvRecord == null || (aRecords.isEmpty() && aaaaRecords.isEmpty())) {
                    return // incomplete answers
                }

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
