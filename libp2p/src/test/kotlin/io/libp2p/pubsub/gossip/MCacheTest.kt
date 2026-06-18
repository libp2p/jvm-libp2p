package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class MCacheTest {

    private var seqNo = 0L

    private fun MCache.getMessageIds(topic: String): Set<MessageId> =
        getGossipMessageIdsByTopic()[topic].orEmpty()

    private fun msg(vararg topics: String): PubsubMessage =
        DefaultPubsubMessage(
            Rpc.Message.newBuilder()
                .also { b -> topics.forEach { b.addTopicIDs(it) } }
                .setSeqno((++seqNo).toBytesBigEndian().toProtobuf())
                .build()
        )

    @Test
    fun `getMessageIds returns ids only for the requested topic`() {
        val mCache = MCache(3, 5)
        val a = msg("topicA")
        val b = msg("topicB")
        val ab = msg("topicA", "topicB")
        mCache.add(a)
        mCache.add(b)
        mCache.add(ab)

        assertThat(mCache.getMessageIds("topicA")).containsExactlyInAnyOrder(a.messageId, ab.messageId)
        assertThat(mCache.getMessageIds("topicB")).containsExactlyInAnyOrder(b.messageId, ab.messageId)
        assertThat(mCache.getMessageIds("topicC")).isEmpty()
    }

    @Test
    fun `getMessageIds dedups a message id present in multiple windows`() {
        val mCache = MCache(3, 5)
        val a = msg("t")
        mCache.add(a)
        mCache.shift()
        mCache.add(a) // same message id appears in a second window

        assertThat(mCache.getMessageIds("t")).containsExactly(a.messageId)
    }

    @Test
    fun `getMessageIds only considers the last gossipSize windows`() {
        val mCache = MCache(gossipSize = 2, historyLength = 5)
        val old = msg("t")
        mCache.add(old)
        mCache.shift()
        val mid = msg("t")
        mCache.add(mid)
        mCache.shift()
        // 'old' is now outside the gossipSize=2 window
        val recent = msg("t")
        mCache.add(recent)

        assertThat(mCache.getMessageIds("t")).containsExactlyInAnyOrder(mid.messageId, recent.messageId)
    }

    @Test
    fun `getGossipMessageIdsByTopic groups ids for the current gossip window`() {
        val mCache = MCache(gossipSize = 2, historyLength = 5)
        val old = msg("topicA")
        mCache.add(old)
        mCache.shift()

        val mid = msg("topicA", "topicB")
        mCache.add(mid)
        mCache.shift()
        mCache.add(mid)
        val recent = msg("topicB")
        mCache.add(recent)

        val idsByTopic = mCache.getGossipMessageIdsByTopic()

        assertThat(idsByTopic["topicA"]).containsExactly(mid.messageId)
        assertThat(idsByTopic["topicB"]).containsExactlyInAnyOrder(mid.messageId, recent.messageId)
        assertThat(idsByTopic).doesNotContainKey("topicC")
    }
}
