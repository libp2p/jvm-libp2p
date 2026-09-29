package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import io.libp2p.core.PeerId
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.PubsubRpcLimits
import io.libp2p.pubsub.RpcMessageCountValidator
import io.libp2p.pubsub.TooLargeMessageException
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import kotlin.random.Random

/**
 * Pins every part's `estimatedMaxFieldCount` to the number [RpcMessageCountValidator] actually
 * charges for that part's standalone RPC.
 *
 * Each part states its count as a constant or a short formula rather than deriving one by protobuf
 * reflection, so nothing makes the two sides agree automatically. This test is what makes them
 * agree: it drives the production queue at a budget of exactly N and again at N-1, and checks the
 * production walker's verdict at both. Passing at N and failing at N-1 on both sides pins the
 * estimate to the inbound count to the field, in either direction - a part that under-counts would
 * be emitted and rejected pre-decode by a peer running this same code.
 */
class RpcPartsFieldCountTest {

    private fun limits(maxTotalFields: Int) = PubsubRpcLimits(
        maxPublishedMessages = null,
        maxTopicsPerPublishedMessage = null,
        rejectEmptyPublishEntries = true,
        maxControlMessageSize = null,
        maxTotalFields = maxTotalFields,
    )

    private fun validate(rpc: Rpc.RPC, maxTotalFields: Int) =
        RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(rpc.toByteArray()), limits(maxTotalFields))

    /**
     * Enqueues one part into a queue budgeted at exactly [expected] fields and asserts that
     * [expected] is both what the queue charges the part and what the inbound walker charges the
     * emitted RPC.
     */
    private fun assertExactFieldCount(expected: Int, addPart: GossipRpcPartsQueue.() -> Unit) {
        // At exactly the part's own count: it enqueues, and the RPC it produces is accepted.
        val exact = DefaultGossipRpcPartsQueue(GossipParams(maxTotalFields = expected))
        exact.addPart()
        val rpc = exact.takeBatch()!!.rpc
        assertThat(validate(rpc, expected))
            .withFailMessage("inbound walker charges more than $expected for %s", rpc)
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)

        // One field tighter: the queue refuses the part, so its estimate is not below `expected`...
        val tight = DefaultGossipRpcPartsQueue(GossipParams(maxTotalFields = expected - 1))
        assertThrows(TooLargeMessageException::class.java) { tight.addPart() }

        // ...and the walker refuses the RPC, so the inbound count is not below `expected` either.
        assertThat(validate(rpc, expected - 1))
            .withFailMessage("inbound walker charges fewer than $expected for %s", rpc)
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    private fun messageId() = Random.nextBytes(20).toWBytes()

    @Test
    fun `publish part counts the publish entry and each set field`() {
        // publish entry + data + topicID
        assertExactFieldCount(3) {
            addPublish(Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x")).addTopicIDs("t").build())
        }

        // publish entry + from + data + seqno + topicID + signature + key
        assertExactFieldCount(7) {
            addPublish(
                Rpc.Message.newBuilder()
                    .setFrom(ByteString.copyFromUtf8("f"))
                    .setData(ByteString.copyFromUtf8("x"))
                    .setSeqno(ByteString.copyFromUtf8("s"))
                    .addTopicIDs("t")
                    .setSignature(ByteString.copyFromUtf8("sig"))
                    .setKey(ByteString.copyFromUtf8("k"))
                    .build()
            )
        }

        // publish entry + data + 5 topicIDs
        assertExactFieldCount(7) {
            addPublish(
                Rpc.Message.newBuilder()
                    .setData(ByteString.copyFromUtf8("x"))
                    .also { b -> repeat(5) { b.addTopicIDs("t$it") } }
                    .build()
            )
        }
    }

    @Test
    fun `publish part counts retained unknown fields and groups`() {
        // A forwarded message keeps the unknown fields its sender included, and protobuf-java
        // re-serializes them, so the inbound walker charges them too.
        val unknowns = UnknownFieldSet.newBuilder()
            .addField(
                99,
                UnknownFieldSet.Field.newBuilder().addVarint(1).addVarint(2).addFixed32(3).build()
            )
            .build()
        // publish entry + data + topicID + 3 unknown scalars
        assertExactFieldCount(6) {
            addPublish(
                Rpc.Message.newBuilder()
                    .setData(ByteString.copyFromUtf8("x"))
                    .addTopicIDs("t")
                    .setUnknownFields(unknowns)
                    .build()
            )
        }

        // A group is one field plus its interior. Here: one group holding one empty nested group.
        val inner = UnknownFieldSet.newBuilder()
            .addField(2, UnknownFieldSet.Field.newBuilder().addGroup(UnknownFieldSet.getDefaultInstance()).build())
            .build()
        val groups = UnknownFieldSet.newBuilder()
            .addField(98, UnknownFieldSet.Field.newBuilder().addGroup(inner).build())
            .build()
        // publish entry + data + topicID + outer group + inner group
        assertExactFieldCount(5) {
            addPublish(
                Rpc.Message.newBuilder()
                    .setData(ByteString.copyFromUtf8("x"))
                    .addTopicIDs("t")
                    .setUnknownFields(groups)
                    .build()
            )
        }
    }

    @Test
    fun `control parts count their fixed shapes`() {
        assertExactFieldCount(4) { addIHave(messageId(), "t") } // control + ihave + topicID + messageID
        assertExactFieldCount(3) { addIWant(messageId()) } // control + iwant + messageID
        assertExactFieldCount(3) { addIDontWant(messageId()) } // control + idontwant + messageID
        assertExactFieldCount(3) { addGraft("t") } // control + graft + topicID
        assertExactFieldCount(3) { addPrune("t") } // control + prune + topicID
        assertExactFieldCount(3) { addSubscribe("t") } // subscriptions + subscribe + topicid
        assertExactFieldCount(3) { addUnsubscribe("t") }
    }

    @Test
    fun `prune part counts backoff and each backoff peer`() {
        // control + prune + topicID + backoff, then peers entry + peerID per peer
        assertExactFieldCount(4) { addPrune("t", 60L, emptyList()) }
        for (n in 1..3) {
            assertExactFieldCount(4 + 2 * n) {
                addPrune("t", 60L, List(n) { PeerId.random() })
            }
        }
    }

    @Test
    fun `control extension part counts each set flag`() {
        // control + extensions + partialMessages
        assertExactFieldCount(3) {
            addControlExtensions(Rpc.ControlExtensions.newBuilder().setPartialMessages(true).build())
        }
        // control + extensions + partialMessages + testExtension
        assertExactFieldCount(4) {
            addControlExtensions(
                Rpc.ControlExtensions.newBuilder().setPartialMessages(true).setTestExtension(true).build()
            )
        }
    }
}
