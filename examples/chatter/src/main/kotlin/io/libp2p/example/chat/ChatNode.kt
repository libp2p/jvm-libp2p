package io.libp2p.example.chat

import com.sun.org.apache.xpath.internal.operations.Mult
import io.libp2p.core.Discoverer
import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.discovery.MDnsDiscovery
import java.lang.Exception
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

typealias OnMessage = (String) -> Unit

class ChatNode(private val printMsg: OnMessage) {
    private data class Friend(
            var name: String,
            val controller: ChatController
    )

    private var currentAlias: String
    private val knownNodes = mutableSetOf<PeerId>()
    private val peerFinder: Discoverer
    private val peers = mutableMapOf<PeerId, Friend>()
    private val privateAddress: InetAddress = privateNetworkAddress()
    private val chatHost = host {
        protocols {
            +Chat(::messageReceived)
        }
        network {
            listen("/ip4/${address}/tcp/0")
        }
    }

    val peerId = chatHost.peerId
    val address: String
        get() { return privateAddress.hostAddress }

    init {
        chatHost.start().get()
        currentAlias = chatHost.peerId.toBase58()

        peerFinder = MDnsDiscovery(chatHost, address = privateAddress)
        peerFinder.onPeerFound { peerFound(it) }
        peerFinder.start()
    } // init

    fun send(message: String) {
        peers.values.forEach { it.controller.send(message) }

        if (message.startsWith("alias "))
            currentAlias = message.substring(6).trim()
    } // send

    fun stop() {
        peerFinder.stop()
        chatHost.stop()
    } // stop

    private fun messageReceived(id: PeerId, msg: String) {
        if (msg == "/who") {
            peers[id]?.controller?.send("alias $currentAlias")
            return
        }
        if (msg.startsWith("alias ")) {
            val friend = peers[id] ?: return
            val previousAlias = friend.name
            val newAlias = msg.substring(6).trim()
            if (previousAlias != newAlias) {
                friend.name = newAlias
                printMsg("$previousAlias is now $newAlias")
            }
            return
        }

        val alias = peers[id]?.name ?: id.toBase58()
        printMsg("$alias > $msg")
    } // messageReceived

    private fun peerFound(info: PeerInfo) {
        if (
                info.peerId == chatHost.peerId ||
                knownNodes.contains(info.peerId)
        )
            return

        knownNodes.add(info.peerId)

        val chatConnection = connectChat(info) ?: return
        chatConnection.first.closeFuture().thenAccept {
            printMsg("${peers[info.peerId]?.name} disconnected.")
            peers.remove(info.peerId)
            knownNodes.remove(info.peerId)
        }
        printMsg("Connected to new peer ${info.peerId}")
        chatConnection.second.send("/who")
        peers[info.peerId] = Friend(
                info.peerId.toBase58(),
                chatConnection.second
        )
    } // peerFound

    private fun connectChat(info: PeerInfo): Pair<Stream, ChatController>? {
        try {
            val chat = Chat(::messageReceived).dial(
                    chatHost,
                    info.peerId,
                    info.addresses[0]
            )
            return Pair(
                    chat.stream.get(),
                    chat.controller.get()
            )
        } catch (e: Exception) {
            return null
        }
    } // connectChat

    companion object {
        private fun privateNetworkAddress(): InetAddress {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val addresses = interfaces.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .sortedBy { it.hostAddress }
            return if (addresses.isNotEmpty())
                addresses[0]
            else
                InetAddress.getLoopbackAddress()
        }
    }
} // class ChatNode
