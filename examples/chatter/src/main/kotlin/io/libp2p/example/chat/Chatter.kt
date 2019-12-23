package io.libp2p.example.chat

import io.libp2p.core.dsl.host
import io.libp2p.protocol.Identify

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

    println("Libp2p Chatter!")
    println("===============")
}