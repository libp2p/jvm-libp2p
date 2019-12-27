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
        message = readLine()

        if (message == null)
            continue

        node.send(message)
    } while ("bye" != message?.trim())

    node.stop()
}

