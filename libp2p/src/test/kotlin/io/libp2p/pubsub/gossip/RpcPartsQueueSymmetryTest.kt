package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.pubsub.PubsubRpcLimits
import io.libp2p.pubsub.RpcMessageCountValidator
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
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
}
