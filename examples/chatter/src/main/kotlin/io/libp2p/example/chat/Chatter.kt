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

lateinit var currentAlias: String
val knownNodes = mutableSetOf<PeerId>()
data class Friend(
    var name: String,
    val controller: ChatController
)
val peers = mutableMapOf<PeerId, Friend>()

fun main() {
    chatHost.start().get()
    currentAlias = chatHost.peerId.toBase58()

    val peerFinder = MDnsDiscovery(chatHost)
    peerFinder.onPeerFound { peerFound(it) }
    peerFinder.start()

    println()
    println("Libp2p Chatter!")
    println("===============")
    println()
    println("This node is ${chatHost.peerId}")
    println()
    println("Enter 'bye' to quit, enter 'alias <name>' to set chat name")
    println()

    var message: String?
    do {
        print(">> ")
        message = readLine()

        if (message == null)
            continue

        peers.values.forEach { it.controller.send(message) }

        if (message.startsWith("alias "))
            currentAlias = message.substring(6).trim()
    } while ("bye" != message?.trim())

    peerFinder.stop()
    chatHost.stop()
}

fun messageReceived(id: PeerId, msg: String) {
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
            println("$previousAlias is now $newAlias")
        }
        return
    }

    val alias = peers[id]?.name ?: id.toBase58()
    println("$alias > $msg")
}

fun peerFound(info: PeerInfo) {
    if (
            info.peerId == chatHost.peerId ||
            knownNodes.contains(info.peerId)
    )
        return

    knownNodes.add(info.peerId)

    val chatConnection = connectChat(info) ?: return
    chatConnection.first.closeFuture().thenAccept {
        println("${peers[info.peerId]?.name} disconnected.")
        peers.remove(info.peerId)
        knownNodes.remove(info.peerId)
    }
    println("Connected to new peer ${info.peerId}")
    chatConnection.second.send("/who")
    peers[info.peerId] = Friend(
            info.peerId.toBase58(),
            chatConnection.second
    )
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