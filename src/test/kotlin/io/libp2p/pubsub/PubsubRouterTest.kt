package io.libp2p.pubsub

import io.libp2p.core.types.toBytesBigEndian
import io.libp2p.core.types.toProtobuf
import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.pubsub.gossip.GossipRouter
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.TimeUnit

class PubsubRouterTest {

    fun newMessage(topic: String, seqNo: Long, data: ByteArray) =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(data.toProtobuf())
            .build()

    @Test
    fun test1() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(GossipRouter())
        val router2 = fuzz.createTestRouter(GossipRouter())
        router2.router.subscribe("topic1")

        router1.connect(router2, LogLevel.ERROR, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg)

        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun test2() {
        scenario2 { FloodRouter() }
        scenario2 { GossipRouter() }
    }

    fun scenario2(routerFactory: () -> PubsubRouterDebug) {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory())
        val router2 = fuzz.createTestRouter(routerFactory())
        val router3 = fuzz.createTestRouter(routerFactory())

        val conn_1_2 = router1.connect(router2, pubsubLogs = LogLevel.ERROR)
        val conn_2_3 = router2.connect(router3, pubsubLogs = LogLevel.ERROR)

        listOf(router1, router2, router3).forEach { it.router.subscribe("topic1", "topic2", "topic3") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))


        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg1)

        Assertions.assertEquals(msg1, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg1, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        val msg2 = newMessage("topic2", 1L, "Hello".toByteArray())
        router2.router.publish(msg2)

        Assertions.assertEquals(msg2, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg2, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        val conn_3_1 = router3.connect(router1, pubsubLogs = LogLevel.ERROR)

        val msg3 = newMessage("topic3", 2L, "Hello".toByteArray())
        router2.router.publish(msg3)

        Assertions.assertEquals(msg3, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg3, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        conn_2_3.disconnect()
        conn_3_1.disconnect()

        val msg4 = newMessage("topic3", 3L, "Hello - 4".toByteArray())
        router2.router.publish(msg4)

        Assertions.assertEquals(msg4, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())
    }
}

