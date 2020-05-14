package io.libp2p.pubsub

import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.PubsubPublisherApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.flood.FloodRouter
import io.netty.buffer.ByteBuf
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PubsubApiTest {

    @Test
    fun testNoFromMessageField() {
        class CustomApi(router: PubsubRouter) : PubsubApiImpl(router) {
            inner class CustomPublisher(privKey: PrivKey, seqId: Long) : PublisherImpl(privKey, seqId) {
                override fun createMessageToSign(data: ByteBuf, vararg topics: Topic): Rpc.Message {
                    // not including 'from' field
                    return Rpc.Message.newBuilder()
                        .addAllTopicIDs(topics.map { it.topic })
                        .setData(data.toByteArray().toProtobuf())
                        .setSeqno(seqCounter.incrementAndGet().toBytesBigEndian().toProtobuf())
                        .build()
                }
            }

            override fun createPublisher(privKey: PrivKey, seqId: Long): PubsubPublisherApi {
                return CustomPublisher(privKey, seqId)
            }
        }

        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createTestRouter(FloodRouter())
        val api1 = CustomApi(router1.router)
        val router2 = fuzz.createTestRouter(FloodRouter())
        val api2 = CustomApi(router2.router)

        router1.connectSemiDuplex(router2)

        val receivedMessages2 = LinkedBlockingQueue<MessageApi>()
        api1.subscribe(Subscriber { println(it) }, Topic("myTopic"))
        api2.subscribe(Subscriber { receivedMessages2 += it }, Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(10))

        val publisher1 = api1.createPublisher(router1.keyPair.first, 777)
        val publishFut = publisher1.publish("Message".toByteArray().toByteBuf(), Topic("myTopic"))

        fuzz.timeController.addTime(Duration.ofSeconds(1))

        Assertions.assertTrue(publishFut.isDone)
        val recMsg = receivedMessages2.poll(1, TimeUnit.SECONDS)
        println(recMsg)
        Assertions.assertNotNull(recMsg)
        Assertions.assertEquals(1, recMsg.topics.size)
        Assertions.assertEquals(Topic("myTopic"), recMsg.topics[0])
        Assertions.assertEquals(778, recMsg.seqId)
        Assertions.assertEquals("Message", recMsg.data.toByteArray().toString(StandardCharsets.UTF_8))
        Assertions.assertEquals(0, recMsg.from.size)
    }
}