package io.libp2p.example.chat

import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.Stream
import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Identify
import io.libp2p.discovery.MDnsDiscovery
import java.util.concurrent.CompletableFuture.runAsync

val chatHost = host {
    protocols {
        +Identify()
        +Chat()
    }
    network {
        listen("/ip4/127.0.0.1/tcp/0")
    }
}

val peers = mutableMapOf<PeerId, ChatController?>()

fun main(args: Array<String>) {
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

fun peerFound(info: PeerInfo) {
    if (info.peerId == chatHost.peerId || peers.contains(info.peerId))
        return
    if (probePeer(info)) {
        println("Connecting to new peer ${info.peerId}")
        peers[info.peerId] = null

        val chatConnection = connectChat(info)
        chatConnection.first.closeFuture().thenAccept {
            println("${info.peerId} disconnected.")
            peers.remove(info.peerId)
        }
        chatConnection.second.send("Hello ${info.peerId}")
        peers[info.peerId] = chatConnection.second
    }
}

fun probePeer(info: PeerInfo): Boolean {
    val identify = Identify().dial(
            chatHost,
            info.peerId,
            info.addresses[0]
    )
    val identifyController = identify.controller.get()
    val remoteIdentity = identifyController.id().get()
    identify.stream.get().close()

    return remoteIdentity.protocolsList.contains(ChatProtocol.announce)
}

fun connectChat(info: PeerInfo): Pair<Stream, ChatController> {
    val chat = Chat().dial(
            chatHost,
            info.peerId,
            info.addresses[0]
    )
    return Pair(
            chat.stream.get(),
            chat.controller.get()
    )
}