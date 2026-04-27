package io.libp2p.pubsub.gossip.extensions

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipTestsBase
import io.libp2p.pubsub.gossip.partialmessages.PublishAction
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

private const val TIMEOUT_MS = 500L

class PartialMessagesOutboundRpcTest : GossipTestsBase() {

    private val topicId = "test-topic"
    private val groupIdBytes = "group-1".toByteArray()

    private fun newTest() = TwoRoutersTest(
        protocol = PubsubProtocol.Gossip_V_1_3,
        enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
        partialMessagesHandler = nopPartialMessagesHandler,
    )

    private fun controlExtensionsWithPartial(): Rpc.RPC =
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().setExtensions(
                Rpc.ControlExtensions.newBuilder().setPartialMessages(true)
            )
        ).build()

    private fun subscribeRpc(
        topic: String,
        requestsPartial: Boolean,
        supportsSendingPartial: Boolean
    ): Rpc.RPC =
        Rpc.RPC.newBuilder().addSubscriptions(
            Rpc.RPC.SubOpts.newBuilder()
                .setTopicid(topic)
                .setSubscribe(true)
                .setRequestsPartial(requestsPartial)
                .setSupportsSendingPartial(supportsSendingPartial)
        ).build()

    private fun TwoRoutersTest.flushRouter() =
        gossipRouter.submitOnEventThread {}.join()

    private fun TwoRoutersTest.peerIdOfMockRouter(): PeerId = router2.peerId

    @Test
    fun `publishPartial delivers partial RPC to peer that requested partial`() {
        val test = newTest()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(subscribeRpc(topicId, requestsPartial = true, supportsSendingPartial = true))
        test.flushRouter()

        val payload = byteArrayOf(1, 2, 3)
        val meta = byteArrayOf(0xAA.toByte())
        val peerId = test.peerIdOfMockRouter()

        val actionsFn = PublishActionsFn<Unit> { _, _ ->
            sequenceOf(peerId to PublishAction(partialMessage = payload, partsMetadata = meta))
        }

        test.gossipRouter.publishPartial(topicId, groupIdBytes, actionsFn)

        val received = test.mockRouter.waitForMessage({ it.hasPartial() }, TIMEOUT_MS)
        assertThat(received.partial.topicID).isEqualTo(topicId)
        assertThat(received.partial.groupID).isEqualTo(ByteString.copyFrom(groupIdBytes))
        assertThat(received.partial.partialMessage.toByteArray()).isEqualTo(payload)
        assertThat(received.partial.partsMetadata.toByteArray()).isEqualTo(meta)
    }

    @Test
    fun `publishPartial omits partialMessage when peer supports but did not request`() {
        val test = newTest()

        // Peer supports sending partial but did NOT request partial messages
        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(subscribeRpc(topicId, requestsPartial = false, supportsSendingPartial = true))
        test.flushRouter()

        val payload = byteArrayOf(1, 2, 3)
        val meta = byteArrayOf(0xAA.toByte())
        val peerId = test.peerIdOfMockRouter()

        val actionsFn = PublishActionsFn<Unit> { _, _ ->
            sequenceOf(peerId to PublishAction(partialMessage = payload, partsMetadata = meta))
        }

        test.gossipRouter.publishPartial(topicId, groupIdBytes, actionsFn)

        val received = test.mockRouter.waitForMessage({ it.hasPartial() }, TIMEOUT_MS)
        // partsMetadata is present; partialMessage MUST be absent (spec MUST)
        assertThat(received.partial.hasPartialMessage()).isFalse()
        assertThat(received.partial.partsMetadata.toByteArray()).isEqualTo(meta)
    }

    @Test
    fun `publishPartial sends nothing when actionsFn returns empty sequence`() {
        val test = newTest()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(subscribeRpc(topicId, requestsPartial = true, supportsSendingPartial = true))
        test.flushRouter()

        val actionsFn = PublishActionsFn<Unit> { _, _ -> emptySequence() }

        test.gossipRouter.publishPartial(topicId, groupIdBytes, actionsFn)
        test.flushRouter()

        assertThat(test.mockRouter.inboundMessages.none { it.hasPartial() }).isTrue()
    }

    @Test
    fun `publishPartial sends nothing when adapter is not configured`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(),
        )
        test.flushRouter()

        val peerId = test.peerIdOfMockRouter()
        val actionsFn = PublishActionsFn<Unit> { _, _ ->
            sequenceOf(peerId to PublishAction(partsMetadata = byteArrayOf(1)))
        }

        test.gossipRouter.publishPartial(topicId, groupIdBytes, actionsFn)
        test.flushRouter()

        assertThat(test.mockRouter.inboundMessages.none { it.hasPartial() }).isTrue()
    }

    @Test
    fun `publishPartial two groups produce two separate RPCs`() {
        val test = newTest()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(subscribeRpc(topicId, requestsPartial = true, supportsSendingPartial = true))
        test.flushRouter()

        val peerId = test.peerIdOfMockRouter()
        val groupA = "group-a".toByteArray()
        val groupB = "group-b".toByteArray()

        test.gossipRouter.publishPartial(
            topicId,
            groupA,
            PublishActionsFn<Unit> { _, _ -> sequenceOf(peerId to PublishAction(partsMetadata = byteArrayOf(1))) }
        )
        test.gossipRouter.publishPartial(
            topicId,
            groupB,
            PublishActionsFn<Unit> { _, _ -> sequenceOf(peerId to PublishAction(partsMetadata = byteArrayOf(2))) }
        )

        val rpc1 = test.mockRouter.waitForMessage({ it.hasPartial() }, TIMEOUT_MS)
        val rpc2 = test.mockRouter.waitForMessage({ it.hasPartial() }, TIMEOUT_MS)

        val groupIds = setOf(
            rpc1.partial.groupID.toByteArray().toList(),
            rpc2.partial.groupID.toByteArray().toList()
        )
        assertThat(groupIds).containsExactlyInAnyOrder(
            groupA.toList(),
            groupB.toList()
        )
    }
}
