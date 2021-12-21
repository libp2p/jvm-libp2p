package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.mux.mplex.DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class GossipTwoHostTest : TwoGossipHostTestBase() {

    override val params = GossipParams(maxGossipMessageSize = DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH * 2)

    @Test
    fun `test message larger than mplex frame`() {
        connect()

        val topic = Topic("topic")

        val messages = mutableListOf<MessageApi>()
        gossip2.subscribe(Subscriber { messages += it }, topic)

        waitForSubscribed(router1, topic.topic)

        val msgSize = DEFAULT_MAX_MPLEX_FRAME_DATA_LENGTH + 10
        val msgBytes = ByteArray(msgSize) { 0xab.toByte() }

        val res = gossip1.createPublisher(null)
            .publish(msgBytes.toByteBuf(), topic)

        res.get(10, TimeUnit.SECONDS)

        waitFor { messages.isNotEmpty() }

        assertThat(messages)
            .hasSize(1)
            .allMatch { it.data.toByteArray().contentEquals(msgBytes) }
    }
}
