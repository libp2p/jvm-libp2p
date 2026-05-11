package io.libp2p.pubsub.gossip.extensions

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipTestsBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Tests for Step 6 — full-message suppression (§5.1).
 *
 * When a peer supports partial messages (ControlExtensions handshake) AND has
 * requested partial delivery for a topic (SubOpts), the gossip router MUST NOT
 * send the full message to that peer in either [broadcastOutbound] or
 * [broadcastInbound]. The client is responsible for pushing parts via
 * [Gossip.publishPartial].
 */
class PartialMessagesFullMsgSuppressionTest : GossipTestsBase() {

    private val topicId = "test-topic"

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
        topic: String = topicId,
        requestsPartial: Boolean,
        supportsSendingPartial: Boolean,
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

    // ── broadcastOutbound ────────────────────────────────────────────────────

    @Test
    fun `broadcastOutbound - full message NOT sent to peer that requested partial`() {
        val test = newTest()

        test.mockRouter.subscribe(topicId)
        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(subscribeRpc(requestsPartial = true, supportsSendingPartial = true))
        test.flushRouter()

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.flushRouter()

        assertThat(test.mockRouter.inboundMessages.none { it.publishCount > 0 }).isTrue()
    }

    @Test
    fun `broadcastOutbound - full message still sent when partial extension disabled`() {
        val test = TwoRoutersTest(protocol = PubsubProtocol.Gossip_V_1_3)

        test.mockRouter.subscribe(topicId)
        test.flushRouter()

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    @Test
    fun `broadcastOutbound - full message still sent when peer did not request partial`() {
        val test = newTest()

        test.mockRouter.subscribe(topicId)
        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        // No requestsPartial flag — peer supports sending but did not request
        test.mockRouter.sendToSingle(subscribeRpc(requestsPartial = false, supportsSendingPartial = true))
        test.flushRouter()

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    @Test
    fun `broadcastOutbound - full message still sent when peer supports partial at node level but no topic sub flag`() {
        val test = newTest()

        test.mockRouter.subscribe(topicId)
        // ControlExtensions: peer supports partial, but no SubOpts requestsPartial
        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.flushRouter()

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    // ── broadcastInbound ─────────────────────────────────────────────────────

    @Test
    fun `broadcastInbound - forwarded full message NOT sent to peer that requested partial`() {
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = nopPartialMessagesHandler,
        )
        test.connectAll()

        // Subscribe mock routers first so gossipRouter.subscribe grafts them immediately.
        test.routers.forEach { it.router.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)

        // mockRouters[1] announces partial support and requests partial for the topic.
        test.mockRouters[1].sendToSingle(controlExtensionsWithPartial())
        test.mockRouters[1].sendToSingle(subscribeRpc(requestsPartial = true, supportsSendingPartial = true))

        // mockRouters[0] sends a full message that gossipRouter would normally forward.
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(newProtoMessage(topicId, 0L, "Hello".toByteArray())).build()
        )
        test.fuzz.timeController.addTime(100)

        assertThat(test.mockRouters[1].inboundMessages.none { it.publishCount > 0 }).isTrue()
    }

    @Test
    fun `broadcastInbound - forwarded full message IS sent to non-partial peer (sanity)`() {
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = nopPartialMessagesHandler,
        )
        test.connectAll()

        test.routers.forEach { it.router.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)

        // mockRouters[1] does NOT request partial — should receive the forwarded message.
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(newProtoMessage(topicId, 0L, "Hello".toByteArray())).build()
        )

        test.mockRouters[1].waitForMessage { it.publishCount > 0 }
    }
}
