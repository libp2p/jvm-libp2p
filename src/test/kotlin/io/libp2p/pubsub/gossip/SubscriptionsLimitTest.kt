package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class SubscriptionsLimitTest : TwoGossipHostTestBase() {
    override val params = GossipParams(maxSubscriptions = 5, floodPublish = true)

    @Test
    fun `new peer subscribed to many topics`() {
        val topics = (0..13).map { Topic("topic-$it") }.toTypedArray()
        gossip1.subscribe(Subscriber {}, *topics)
        val messages2 = mutableListOf<MessageApi>()
        gossip2.subscribe(Subscriber { messages2 += it }, *topics)

        connect()
        waitForSubscribed(router1, "topic-13")
        waitForSubscribed(router2, "topic-13")

        val topics1 = router1.getPeerTopics().join().values.first()
        assertThat(topics1).containsExactlyInAnyOrderElementsOf(topics.map { it.topic })
        val topics2 = router2.getPeerTopics().join().values.first()
        assertThat(topics2).containsExactlyInAnyOrderElementsOf(topics.map { it.topic })

        val msg1Promise =
            gossip1.createPublisher(null).publish(byteArrayOf(11).toByteBuf(), Topic("topic-13"))

        assertDoesNotThrow { msg1Promise.join() }
        waitFor { messages2.isNotEmpty() }
        assertThat(messages2)
            .hasSize(1)
            .allMatch {
                it.topics == listOf(Topic("topic-13")) &&
                    it.data.toByteArray().contentEquals(byteArrayOf(11))
            }
    }

    @Test
    fun `new peer subscribed to few topics`() {
        val topics = (0..4).map { Topic("topic-$it") }.toTypedArray()
        gossip1.subscribe(Subscriber { }, *topics)
        gossip2.subscribe(Subscriber { }, *topics)

        connect()
        waitForSubscribed(router1, "topic-4")
        waitForSubscribed(router2, "topic-4")

        val topics1 = router1.getPeerTopics().join().values.first()
        assertThat(topics1).containsExactlyInAnyOrderElementsOf(topics.map { it.topic })
        val topics2 = router2.getPeerTopics().join().values.first()
        assertThat(topics2).containsExactlyInAnyOrderElementsOf(topics.map { it.topic })
    }

    @Test
    fun `existing peer subscribed to many topics`() {
        gossip1.subscribe(Subscriber { }, Topic("test-topic"))
        gossip2.subscribe(Subscriber { }, Topic("test-topic"))

        connect()
        waitForSubscribed(router1, "test-topic")
        waitForSubscribed(router2, "test-topic")

        val topics = (0..13).map { Topic("topic-$it") }.toTypedArray()
        gossip1.subscribe(Subscriber { }, *topics)
        gossip2.subscribe(Subscriber { }, *topics)

        waitForSubscribed(router1, "topic-13")
        waitForSubscribed(router2, "topic-13")

        val topics1 = router1.getPeerTopics().join().values.first()
        assertThat(topics1).containsExactlyInAnyOrderElementsOf(topics.map { it.topic } + "test-topic")
        val topics2 = router2.getPeerTopics().join().values.first()
        assertThat(topics2).containsExactlyInAnyOrderElementsOf(topics.map { it.topic } + "test-topic")
    }
}
