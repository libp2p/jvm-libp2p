package io.libp2p.example.gossippubsubdiscovery

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteBuf

fun main() {
    val node = Node()

    println()
    println("Libp2p Example Publisher")
    println("===============")
    println()
    println("This node is ${node.peerId}, listening on ${node.address}")
    println()
    println()

    val publisher = node.createPublisher()
    var i = 0
    do {
        try {
            publisher.publish(i++.toString().toByteArray().toByteBuf(), Topic("HelloWorld"))
            Thread.sleep(1000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } while (true)
}