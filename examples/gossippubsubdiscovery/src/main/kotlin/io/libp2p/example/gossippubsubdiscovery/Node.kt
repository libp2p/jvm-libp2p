package io.libp2p.example.gossippubsubdiscovery

import io.libp2p.core.Discoverer
import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.pubsub.PubsubPublisherApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.discovery.MDnsDiscovery
import io.libp2p.pubsub.gossip.Gossip
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class Node {
    private val knownNodes = mutableSetOf<PeerId>()
    private val peerFinder: Discoverer
    private val peers = mutableSetOf<PeerId>()
    private val privateAddress: InetAddress = privateNetworkAddress()
    private val gossip: Gossip = Gossip()
    private val host = host {
        protocols {
            gossip
        }
        network {
            listen("/ip4/$address/tcp/0")
        }
    }

    val peerId = host.peerId
    val address: String
        get() {
            return privateAddress.hostAddress
        }

    init {
        host.addConnectionHandler(gossip)
        host.addProtocolHandler(gossip)
        host.start().get()
        peerFinder = MDnsDiscovery(host, address = privateAddress)
        peerFinder.newPeerFoundListeners += { peerFound(it) }
        peerFinder.start()
    } // init

    fun subscribe(topic: String) {
        gossip.subscribe(Subscriber { println(it) }, Topic(topic))
    } //subscribe

    fun createPublisher(): PubsubPublisherApi {
        return gossip.createPublisher(host.privKey)
    } //createPublisher

    private fun peerFound(info: PeerInfo) {
        if (
            info.peerId == host.peerId ||
            knownNodes.contains(info.peerId)
        ) {
            return
        }

        knownNodes.add(info.peerId)

        val chatConnection = connectChat(info)
        chatConnection.closeFuture().thenAccept {
            peers.remove(info.peerId)
            knownNodes.remove(info.peerId)
            println("Disconnected from peer ${info.peerId}")
        }
        println("Connected to new peer ${info.peerId}")
        peers.add(info.peerId)
    } // peerFound

    private fun connectChat(info: PeerInfo): Stream {
        try {
            val stream = gossip.dial(host, info.peerId, info.addresses[0]).stream.get()
            gossip.subscribe(Subscriber { println(it) }, Topic("HelloWorld"))
            return stream
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    } // connectChat

    companion object {
        private fun privateNetworkAddress(): InetAddress {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val addresses = interfaces.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .sortedBy { it.hostAddress }
            return if (addresses.isNotEmpty()) {
                addresses[0]
            } else {
                InetAddress.getLoopbackAddress()
            }
        }
    }
}