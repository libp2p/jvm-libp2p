package io.libp2p.example.chat

import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.protocol.Identify
import io.libp2p.discovery.MDnsDiscovery

val peers = mutableSetOf<PeerId>()

fun main(args: Array<String>) {
    val chatHost = host {
        protocols {
            +Identify()
        }
        network {
            listen("/ip4/127.0.0.1/tcp/0")
        }
    }

    chatHost.start().get()

    val peerFinder = MDnsDiscovery(chatHost)
    peerFinder.onPeerFound {
        if (it.peerId == chatHost.peerId || peers.contains(it.peerId))
            return@onPeerFound
        println("New peer ${it.peerId}")
        peers.add(it.peerId)
    }
    peerFinder.start()

    println("Libp2p Chatter!")
    println("===============")
}