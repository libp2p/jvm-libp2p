package io.libp2p.core

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class HostTest {

    @Disabled
    @Test
    fun testHost() {
        // Let's create a host! This is a nice fluent builder, kindly sponsored by Kotlin.
//        val host = Host.create {
//            secureChannels {
//                this[ProtocolMatcher(Mode.STRICT, name = "test")] = SecIoSecureChannel()
//            }
//        }
//
//        // Dummy peer ID.
//        val id = PeerId(ByteArray(0))
//
//        // What is the status of this peer? Are we connected to it? Do we know them (i.e. have addresses for them?)
//        host.peer(id).status()
//
//        val connection = host.peer(id).connect()
//        // Disconnect this peer.
//        host.peer(id).disconnect()
//        // Get a connection, if any.
//        host.peer(id).connection()
//
//        // Get this peer's addresses.
//        val addrs = host.peer(id).addrs()
//
//        // Create a stream
//        host.peer(id).streams().create("/eth2/1.0.0")
    }
}