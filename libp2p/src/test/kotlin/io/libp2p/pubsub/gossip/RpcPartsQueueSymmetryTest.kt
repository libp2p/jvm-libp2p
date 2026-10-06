package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import com.google.protobuf.UnknownFieldSet
import io.libp2p.pubsub.PubsubRpcLimits
import io.libp2p.pubsub.RpcMessageCountValidator
import io.libp2p.pubsub.TooLargeMessageException
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Symmetry guard: the outbound batcher must never emit an RPC that this same library's inbound
 * validator would reject pre-decode. The hazard is publish-heavy traffic - a publish's `data`
 * payload is exempt from the control-byte budget but each `data` field still costs one field on the
 * inbound side, so a burst of small-envelope publishes can stay within the byte budgets while
 * overflowing [GossipParams.maxTotalFields] unless the batcher also splits on field count.
 */
class RpcPartsQueueSymmetryTest {

    private val params = GossipParams()

    // Mirrors GossipRouter.rpcLimits.
    private val limits = PubsubRpcLimits(
        maxPublishedMessages = params.maxPublishedMessages,
        maxTopicsPerPublishedMessage = params.maxTopicsPerPublishedMessage,
        rejectEmptyPublishEntries = true,
        maxControlMessageSize = params.maxControlMessageSize,
        maxTotalFields = params.maxTotalFields,
    )

    // Minimal publish: tiny data + one short topic. Field-dense but byte-cheap - the exact shape
    // that slips past the byte budgets. ~3 inbound fields each (publish envelope + data + topicID).
    private fun minimalPublish() =
        Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x")).addTopicIDs("t").build()

    private fun validate(rpc: Rpc.RPC) =
        RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(rpc.toByteArray()), limits)

    @Test
    fun `every batch of minimal publishes passes the inbound validator`() {
        val count = 80_000 // 80k x ~3 fields = ~240k inbound fields, far over maxTotalFields 65536.

        // The hazard is real: all parts merged into one RPC would be rejected pre-decode.
        val monolith = Rpc.RPC.newBuilder().also { b -> repeat(count) { b.addPublish(minimalPublish()) } }.build()
        assertThat(validate(monolith))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)

        val queue = DefaultGossipRpcPartsQueue(params)
        repeat(count) { queue.addPublish(minimalPublish()) }

        var batches = 0
        var publishesSeen = 0
        while (!queue.isEmpty()) {
            val rpc = queue.takeBatch()!!.rpc
            batches++
            publishesSeen += rpc.publishCount
            assertThat(validate(rpc))
                .withFailMessage("batch %d with %d publishes was rejected inbound", batches, rpc.publishCount)
                .isEqualTo(RpcMessageCountValidator.Result.Accepted)
        }

        assertThat(batches).isGreaterThan(1) // the field budget forced a split
        assertThat(publishesSeen).isEqualTo(count) // no publish lost across the split
    }

    @Test
    fun `a single part exceeding the field budget is rejected at enqueue, never emitted`() {
        // takeBatch emits a lone over-budget part rather than stall, so the queue must refuse one at
        // enqueue - otherwise it would emit an RPC RpcMessageCountValidator rejects pre-decode.
        val tightParams = GossipParams(maxTotalFields = 10)
        val queue = DefaultGossipRpcPartsQueue(tightParams)

        // One publish with 20 topicIDs: ~22 inbound fields (envelope + data + 20 topics) > 10.
        val fat = Rpc.Message.newBuilder()
            .setData(ByteString.copyFromUtf8("x"))
            .also { b -> repeat(20) { b.addTopicIDs("t$it") } }
            .build()

        assertThrows(TooLargeMessageException::class.java) { queue.addPublish(fat) }
        assertThat(queue.isEmpty()).isTrue()
    }

    @Test
    fun `every batch of publishes carrying unknown fields passes the inbound validator`() {
        // A forwarded message can retain unknown protobuf fields. allFields excludes them but the
        // inbound walker charges them, so the outbound field estimate must count them too.
        val unknownVarints = 100
        val count = 2_000 // (3 known + 100 unknown) x 2000 = ~206k inbound fields, over 65536.

        fun withUnknowns(): Rpc.Message {
            val unknowns = UnknownFieldSet.newBuilder()
                .addField(
                    99,
                    UnknownFieldSet.Field.newBuilder()
                        .also { f -> repeat(unknownVarints) { f.addVarint(1) } }
                        .build()
                )
                .build()
            return Rpc.Message.newBuilder()
                .setData(ByteString.copyFromUtf8("x"))
                .addTopicIDs("t")
                .setUnknownFields(unknowns)
                .build()
        }

        val monolith = Rpc.RPC.newBuilder().also { b -> repeat(count) { b.addPublish(withUnknowns()) } }.build()
        assertThat(validate(monolith))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)

        val queue = DefaultGossipRpcPartsQueue(params)
        repeat(count) { queue.addPublish(withUnknowns()) }

        var batches = 0
        var publishesSeen = 0
        while (!queue.isEmpty()) {
            val rpc = queue.takeBatch()!!.rpc
            batches++
            publishesSeen += rpc.publishCount
            assertThat(validate(rpc))
                .withFailMessage("batch %d with %d publishes was rejected inbound", batches, rpc.publishCount)
                .isEqualTo(RpcMessageCountValidator.Result.Accepted)
        }

        assertThat(batches).isGreaterThan(1)
        assertThat(publishesSeen).isEqualTo(count)
    }

    @Test
    fun `every batch of publishes carrying unknown groups passes the inbound validator`() {
        // Empty and nested unknown groups exercise END_GROUP accounting: the inbound walker must not
        // charge END delimiters, so it agrees with the outbound countUnknownFields (group = 1 +
        // interior). Under-counting here would batch a frame the receiver rejects.
        val groupsPerMessage = 100
        val count = 2_000

        fun withGroups(): Rpc.Message {
            val groups = UnknownFieldSet.Field.newBuilder()
            repeat(groupsPerMessage) {
                // Nested: one empty group inside each group, so both nesting and empty bodies count.
                val inner = UnknownFieldSet.newBuilder()
                    .addField(2, UnknownFieldSet.Field.newBuilder().addGroup(UnknownFieldSet.getDefaultInstance()).build())
                    .build()
                groups.addGroup(inner)
            }
            val unknowns = UnknownFieldSet.newBuilder().addField(98, groups.build()).build()
            return Rpc.Message.newBuilder()
                .setData(ByteString.copyFromUtf8("x"))
                .addTopicIDs("t")
                .setUnknownFields(unknowns)
                .build()
        }

        val monolith = Rpc.RPC.newBuilder().also { b -> repeat(count) { b.addPublish(withGroups()) } }.build()
        assertThat(validate(monolith))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)

        val queue = DefaultGossipRpcPartsQueue(params)
        repeat(count) { queue.addPublish(withGroups()) }

        var batches = 0
        var publishesSeen = 0
        while (!queue.isEmpty()) {
            val rpc = queue.takeBatch()!!.rpc
            batches++
            publishesSeen += rpc.publishCount
            assertThat(validate(rpc))
                .withFailMessage("batch %d with %d publishes was rejected inbound", batches, rpc.publishCount)
                .isEqualTo(RpcMessageCountValidator.Result.Accepted)
        }

        assertThat(batches).isGreaterThan(1)
        assertThat(publishesSeen).isEqualTo(count)
    }
}
