package io.libp2p.pubsub

import io.libp2p.core.Connection
import io.libp2p.core.Stream
import io.libp2p.core.security.secio.TestChannel
import io.libp2p.core.security.secio.interConnect
import io.libp2p.core.types.toProtobuf
import io.libp2p.core.util.netty.nettyInitializer
import io.libp2p.pubsub.flood.FloodRouter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.function.Consumer

class PubsubRouterTest {

    @Test
    fun test1() {
        val router1 = FloodRouter()
        val ch1 = TestChannel(LoggingHandler("#1", LogLevel.INFO), nettyInitializer {
            val conn1 = Connection(TestChannel())
            val stream1 = Stream(it, conn1)
            router1.addPeer(stream1)
        })

        val router2 = FloodRouter()
        val ch2 = TestChannel(LoggingHandler("#2", LogLevel.INFO), nettyInitializer {
            val conn2 = Connection(TestChannel())
            val stream2 = Stream(it, conn2)
            router2.addPeer(stream2)
        })

        interConnect(ch1, ch2)

        router1.setHandler(Consumer {
            println("Router1 inbound: $it")
        })
        router2.setHandler(Consumer {
            println("Router2 inbound: $it")
        })

        router1.publish(Rpc.Message.newBuilder()
            .addTopicIDs("topic1")
            .setSeqno(ByteArray(8).toProtobuf())
            .setData("Hello".toByteArray().toProtobuf())
            .build()
        )
    }
}