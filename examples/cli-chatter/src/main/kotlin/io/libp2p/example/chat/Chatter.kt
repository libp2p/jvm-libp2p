package io.libp2p.example.chat

fun main() {
    val node = ChatNode(::println)

    println()
    println("Libp2p Chatter!")
    println("===============")
    println()
    println("This node is ${node.peerId}, listening on ${node.address}")
    println()
    println("Enter 'bye' to quit, enter 'alias <name>' to set chat name")
    println()

    var message: String?
    do {
        print(">> ")
        message = readLine()?.trim()

        if (message == null || message.isEmpty())
            continue

        node.send(message)
    } while ("bye" != message)

    node.stop()
}
