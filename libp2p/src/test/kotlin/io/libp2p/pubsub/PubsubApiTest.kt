package io.libp2p.pubsub

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toLongBigEndian
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PubsubApiTest {

    @Test
    fun testNoFromOrSeqNoMessageField() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()
        val api2 = createPubsubApi(router2.router)

        router1.connectSemiDuplex(router2)

        val receivedMessages2 = LinkedBlockingQueue<MessageApi>()
        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        api2.subscribe(Subscriber { receivedMessages2 += it }, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        // No from, signature or seqNo
        val publisher1 = api1.createPublisher(null) { null }
        val publishFut = publisher1
            .publishExt("Message".toByteArray().toByteBuf(), null, null, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val recMsg = receivedMessages2.poll(1, TimeUnit.SECONDS)
        Assertions.assertNotNull(recMsg)
        assertEquals(1, recMsg.topics.size)
        assertEquals(Topic("myTopic"), recMsg.topics[0])
        Assertions.assertNull(recMsg.seqId)
        assertEquals("Message", recMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
        Assertions.assertNull(recMsg.from)
    }

    @Test
    fun testNoSenderPrivateKey() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()

        router1.connectSemiDuplex(router2)

        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        router2.router.subscribe("myTopic")

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        val publisher1 = api1.createPublisher(null, 777)
        val publishFut = publisher1.publish("Message".toByteArray().toByteBuf(), Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val rawMsg = router2.inboundMessages.poll(1, TimeUnit.SECONDS)!!.protobufMessage
        println(rawMsg)
        assertFalse(rawMsg.hasSignature())
        assertFalse(rawMsg.hasFrom())
        assertEquals("Message", rawMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
    }

    @Test
    fun testPublishExt() {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createFloodRouter()
        val api1 = createPubsubApi(router1.router)
        val router2 = fuzz.createFloodRouter()

        router1.connectSemiDuplex(router2)

        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        router2.router.subscribe("myTopic")

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        val publisher1 = api1.createPublisher(null, 777)
        val publishFut =
            publisher1.publishExt("Message".toByteArray().toByteBuf(), byteArrayOf(1, 2, 3), 333, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val rawMsg = router2.inboundMessages.poll(1, TimeUnit.SECONDS)!!.protobufMessage
        println(rawMsg)
        assertFalse(rawMsg.hasSignature())
        assertEquals(333, rawMsg.seqno.toByteArray().copyOfRange(0, 8).toLongBigEndian())
        assertArrayEquals(byteArrayOf(1, 2, 3), rawMsg.from.toByteArray())
        assertEquals("Message", rawMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
    }
}
