package io.libp2p.example.gossippubsubdiscovery

fun main() {
    val node = Node()

    println()
    println("Libp2p Example Subscriber!")
    println("===============")
    println()
    println("This node is ${node.peerId}, listening on ${node.address}")
    println()
    println()

    node.subscribe("HelloWorld")
    Thread.sleep(100000)
}