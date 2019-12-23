package io.libp2p.example.chat

import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.discovery.MDnsDiscovery
import java.lang.Exception

val chatHost = host {
    protocols {
        +Chat(::messageReceived)
    }
    network {
        listen("/ip4/127.0.0.1/tcp/0")
    }
}

val knownHosts = mutableSetOf<PeerId>()
val peers = mutableMapOf<PeerId, ChatController?>()

fun main() {
    chatHost.start().get()

    val peerFinder = MDnsDiscovery(chatHost)
    peerFinder.onPeerFound { peerFound(it) }
    peerFinder.start()

    println()
    println("Libp2p Chatter!")
    println("===============")
    println()
    println("This node is ${chatHost.peerId}")
    println()

    var message: String?
    do {
        print(">> ")
        message = readLine()

        if (message != null)
            peers.values.filterNotNull().forEach { it.send(message) }
    } while (!message.equals("bye"))

    peerFinder.stop()
    chatHost.stop()
}

fun messageReceived(id: PeerId, msg: String) {
    println("${id} > ${msg}")
    if (!peers.contains(id)) println("   ... someone new ...")
}

fun peerFound(info: PeerInfo) {
    if (
            info.peerId.equals(chatHost.peerId) ||
            knownHosts.contains(info.peerId)
    )
        return

    knownHosts.add(info.peerId)
    val chatConnection = connectChat(info)

    if (chatConnection == null)
        return

    chatConnection.first.closeFuture().thenAccept {
        println("${info.peerId} disconnected.")
        peers.remove(info.peerId)
        knownHosts.remove(info.peerId)
    }
    chatConnection.second.send("Hello")
    println("Connected to new peer ${info.peerId}")
    peers[info.peerId] = chatConnection.second
}

fun connectChat(info: PeerInfo): Pair<Stream, ChatController>? {
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
}