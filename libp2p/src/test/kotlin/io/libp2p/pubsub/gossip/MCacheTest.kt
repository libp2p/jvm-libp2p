package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.PubsubMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class MCacheTest {

    private var seqNo = 0L

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
    fun `getMessageIds reflects messages added after an earlier query (snapshot invalidation on add)`() {
        val mCache = MCache(3, 5)
        val a = msg("t")
        mCache.add(a)
        // populate the internal snapshot
        assertThat(mCache.getMessageIds("t")).containsExactly(a.messageId)

        val b = msg("t")
        mCache.add(b)
        // a stale snapshot would miss 'b'
        assertThat(mCache.getMessageIds("t")).containsExactlyInAnyOrder(a.messageId, b.messageId)
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
    fun `getMessageIds only considers the last gossipSize windows (snapshot invalidation on shift)`() {
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
}
