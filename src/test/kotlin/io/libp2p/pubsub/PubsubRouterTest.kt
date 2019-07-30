package io.libp2p.pubsub

import io.libp2p.core.Connection
import io.libp2p.core.Stream
import io.libp2p.core.security.secio.TestChannel
import io.libp2p.core.security.secio.interConnect
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.toBytesBigEndian
import io.libp2p.core.types.toProtobuf
import io.libp2p.core.util.netty.nettyInitializer
import io.libp2p.pubsub.flood.FloodRouter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class PubsubRouterTest {

    fun newMessage(topic: String, seqNo: Long, data: ByteArray) =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(data.toProtobuf())
            .build()

    @Test
    fun test1() {
        val router1 = TestRouter("#1")
        val router2 = TestRouter("#2")

        router1.connect(router2)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg)

        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun test2() {
        val router1 = TestRouter()
        val router2 = TestRouter()
        val router3 = TestRouter()

        router1.connect(router2)
        router2.connect(router3)

        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg1)

        Assertions.assertEquals(msg1, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg1, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        val msg2 = newMessage("topic2", 0L, "Hello".toByteArray())
        router2.router.publish(msg2)

        Assertions.assertEquals(msg2, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg2, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        router3.connect(router1)

        val msg3 = newMessage("topic3", 0L, "Hello".toByteArray())
        router2.router.publish(msg3)

        Assertions.assertEquals(msg3, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg3, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())
    }
}

class TestRouter(val loggerName: String? = null) {

    val inboundMessages = LinkedBlockingQueue<Rpc.Message>()
    var routerHandler by lazyVar { Consumer<Rpc.Message> {
        inboundMessages += it
    } }
    var routerInstance by lazyVar { FloodRouter() }
    var router by lazyVar { routerInstance.also { it.setHandler(routerHandler) } }

    private fun newChannel() =
        TestChannel(
            nettyInitializer {
                if (loggerName != null) {
                    it.pipeline().addFirst(LoggingHandler(loggerName, LogLevel.ERROR))
                }
                val conn1 = Connection(TestChannel())
                val stream1 = Stream(it, conn1)
                router.addPeer(stream1)
            })

    fun connect(another: TestRouter): Pair<TestChannel, TestChannel> {
        val thisChannel = newChannel()
        val anotherChannel = another.newChannel()
        interConnect(thisChannel, anotherChannel)
        return thisChannel to anotherChannel
    }
}