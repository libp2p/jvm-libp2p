package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.PubsubRpcLimits
import io.libp2p.pubsub.RpcMessageCountValidator
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.TimeUnit

/**
 * Characterises what does and does not bound the response volume an IWANT can draw, after the
 * per-type inbound counters were consolidated into [GossipParams.maxControlMessageSize].
 *
 * The first two tests come from @tbenr's review of #525: a budget-valid IWANT carries more ids than
 * the removed `maxIWantMessageIds` cap allowed, and the responses dwarf the request. Both are true.
 *
 * The last two exist because that on its own does not argue for restoring the cap. It was a per-RPC
 * check with no cross-RPC state, so splitting one oversized IWANT into two under-cap ones restores
 * the full response volume. What actually bounds a peer is [GossipParams.gossipRetransmission],
 * which is also what the gossipsub v1.1 spec nominates for IWANT spam, and which this change
 * leaves untouched.
 */
class IWantByteBudgetAmplificationTest : GossipTestsBase() {

    @Test
    fun `byte budget accepts more IWANT ids than the former count cap`() {
        val formerMaxIWantMessageIds = 5_000
        val requestedMessages = formerMaxIWantMessageIds * 2
        val test = TwoRoutersTest()

        val messageIds = (0 until requestedMessages).map {
            newMessage("topic", it.toLong(), ByteArray(0)).messageId.toProtobuf()
        }
        val request = iWantRequest(messageIds)

        assertThat(request.control.iwantList.sumOf { it.messageIDsCount })
            .isEqualTo(requestedMessages)
            .isGreaterThan(formerMaxIWantMessageIds)
        assertThat(request.serializedSize)
            .isLessThanOrEqualTo(test.gossipRouter.params.maxControlMessageSize)
        assertThat(
            RpcMessageCountValidator.validate(
                Unpooled.wrappedBuffer(request.toByteArray()),
                PubsubRpcLimits.NONE.copy(
                    maxControlMessageSize = test.gossipRouter.params.maxControlMessageSize
                )
            )
        ).isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `budget-valid IWANT can amplify response payload over one hundred times`() {
        val requestedMessages = 10_000
        val payloadSize = 1_024
        val test = TwoRoutersTest()

        val cachedMessages = (0 until requestedMessages).map {
            newMessage("topic", it.toLong(), ByteArray(payloadSize))
        }
        cachedMessages.forEach(test.gossipRouter.mCache::add)

        val request = iWantRequest(cachedMessages.map { it.messageId.toProtobuf() })

        // With no publish payload in this RPC, serializedSize is also its charged control size.
        assertThat(request.serializedSize)
            .isLessThanOrEqualTo(test.gossipRouter.params.maxControlMessageSize)

        test.mockRouter.sendToSingle(request)

        var responseCount = 0
        var responsePayloadBytes = 0L
        while (responseCount < requestedMessages) {
            val response = test.mockRouter.inboundMessages.poll(5, TimeUnit.SECONDS)
            assertThat(response).isNotNull()
            responseCount += response.publishCount
            responsePayloadBytes += response.publishList.sumOf { it.data.size().toLong() }
        }

        assertThat(responseCount).isEqualTo(requestedMessages)
        assertThat(responsePayloadBytes)
            .isEqualTo(requestedMessages.toLong() * payloadSize)
            .isGreaterThan(test.gossipRouter.params.maxControlMessageSize.toLong())
            .isGreaterThan(request.serializedSize.toLong() * 100)
    }

    /**
     * Counterfactual to the test above: the removed `maxIWantMessageIds` was a per-RPC check, so an
     * attacker restores the full response volume by splitting one oversized IWANT into two that each
     * sit under the cap. Same request bytes on the wire, same 10.24 MB of responses.
     */
    @Test
    fun `splitting an IWANT under the former count cap yields the same response volume`() {
        val formerMaxIWantMessageIds = 5_000
        val requestedMessages = formerMaxIWantMessageIds * 2
        val payloadSize = 1_024
        val test = TwoRoutersTest()

        val cachedMessages = (0 until requestedMessages).map {
            newMessage("topic", it.toLong(), ByteArray(payloadSize))
        }
        cachedMessages.forEach(test.gossipRouter.mCache::add)

        val requests = cachedMessages
            .map { it.messageId.toProtobuf() }
            .chunked(formerMaxIWantMessageIds)
            .map(::iWantRequest)

        // Each half would have been accepted by the removed per-RPC count check.
        assertThat(requests.map { r -> r.control.iwantList.sumOf { it.messageIDsCount } })
            .allMatch { it <= formerMaxIWantMessageIds }

        requests.forEach(test.mockRouter::sendToSingle)

        val (responseCount, responsePayloadBytes) = drainResponses(test, requestedMessages)

        assertThat(responseCount).isEqualTo(requestedMessages)
        assertThat(responsePayloadBytes).isEqualTo(requestedMessages.toLong() * payloadSize)
    }

    /**
     * What actually bounds IWANT amplification per peer: [GossipParams.gossipRetransmission] caps how
     * many times any single cached message is served to the same peer, independent of how many ids a
     * request carries or how the request is split across RPCs.
     */
    @Test
    fun `gossipRetransmission caps repeat IWANTs for the same messages`() {
        val requestedMessages = 1_000
        val payloadSize = 1_024
        val test = TwoRoutersTest()
        val retransmission = test.gossipRouter.params.gossipRetransmission

        val cachedMessages = (0 until requestedMessages).map {
            newMessage("topic", it.toLong(), ByteArray(payloadSize))
        }
        cachedMessages.forEach(test.gossipRouter.mCache::add)

        val request = iWantRequest(cachedMessages.map { it.messageId.toProtobuf() })
        val attempts = retransmission + 2
        repeat(attempts) { test.mockRouter.sendToSingle(request) }

        val servedAtMost = requestedMessages * retransmission
        val (responseCount, responsePayloadBytes) = drainResponses(test, servedAtMost)

        assertThat(responseCount)
            .isEqualTo(servedAtMost)
            .isLessThan(requestedMessages * attempts)
        assertThat(responsePayloadBytes).isEqualTo(servedAtMost.toLong() * payloadSize)
        // Nothing further is served for the remaining attempts.
        assertThat(test.mockRouter.inboundMessages.poll(1, TimeUnit.SECONDS)).isNull()
    }

    private fun drainResponses(test: TwoRoutersTest, expectedCount: Int): Pair<Int, Long> {
        var responseCount = 0
        var responsePayloadBytes = 0L
        while (responseCount < expectedCount) {
            val response = test.mockRouter.inboundMessages.poll(5, TimeUnit.SECONDS)
            assertThat(response).isNotNull()
            responseCount += response.publishCount
            responsePayloadBytes += response.publishList.sumOf { it.data.size().toLong() }
        }
        return responseCount to responsePayloadBytes
    }

    private fun iWantRequest(messageIds: Iterable<ByteString>): Rpc.RPC =
        Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .addIwant(
                        Rpc.ControlIWant.newBuilder()
                            .addAllMessageIDs(messageIds)
                    )
            )
            .build()
}
