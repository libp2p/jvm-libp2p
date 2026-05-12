package io.libp2p.pubsub.gossip.extensions

import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipTestsBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Tests for Step 7 — IDONTWANT suppression (§5.2).
 *
 * When we have requested partial messages for topic T and a peer supports
 * sending partial for T, the gossip router MUST NOT send IDONTWANT to that peer.
 * Sending IDONTWANT would be redundant — the peer is expected to deliver partial
 * RPCs instead of full messages.
 */
class PartialMessagesIDontWantSuppressionTest : GossipTestsBase() {

    private val topicId = "test-topic"

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

    /**
     * Creates a 3-router test network (gossipRouter + 3 mock routers) with all
     * peers grafted into the mesh. Uses Gossip_V_1_3 with the partial-messages
     * extension enabled. mockRouters[0] acts as publisher; [1] and [2] are gossip
     * recipients.
     */
    private fun startNetwork(): ManyRoutersTest {
        val test = ManyRoutersTest(
            mockRouterCount = 3,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5),
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = nopPartialMessagesHandler,
        )
        test.connectAll()

        test.mockRouters.forEach { it.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)

        // Two heartbeats to ensure all peers are GRAFTed into the mesh.
        test.fuzz.timeController.addTime(2.seconds)

        return test
    }

    @Test
    fun `IDONTWANT not sent to peer that supports sending partial when we request partial`() {
        val test = startNetwork()

        // We (gossipRouter) request partial for this topic.
        test.gossipRouter.setTopicPartialFlags(topicId, requestsPartial = true, supportsSendingPartial = false)

        // mockRouters[2] (partial-peer): announces support at node level and for this topic.
        test.mockRouters[2].sendToSingle(controlExtensionsWithPartial())
        test.mockRouters[2].sendToSingle(subscribeRpc(requestsPartial = false, supportsSendingPartial = true))

        // Ensure flags are applied before the message arrives.
        test.gossipRouter.submitOnEventThread {}.join()

        // Publisher sends a message, triggering IDONTWANT emission to mesh peers.
        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )
        test.fuzz.timeController.addTime(100.millis)

        // mockRouters[2] (partial-peer) MUST NOT receive IDONTWANT.
        val iDontWantsToPartialPeer = test.mockRouters[2].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.idontwantList }
        assertThat(iDontWantsToPartialPeer).isEmpty()

        // mockRouters[1] (non-partial peer) MUST receive IDONTWANT.
        val iDontWantsToNonPartialPeer = test.mockRouters[1].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.idontwantList }
        assertThat(iDontWantsToNonPartialPeer).isNotEmpty()
    }

    @Test
    fun `IDONTWANT still sent when partial extension is disabled`() {
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5),
        )
        test.connectAll()
        test.mockRouters.forEach { it.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)
        test.fuzz.timeController.addTime(2.seconds)

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )
        test.fuzz.timeController.addTime(100.millis)

        val iDontWants = test.mockRouters[1].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.idontwantList }
        assertThat(iDontWants).isNotEmpty()
    }

    @Test
    fun `IDONTWANT still sent when we do not request partial for the topic`() {
        val test = startNetwork()

        // We do NOT set requestsPartial=true for the topic.
        // mockRouters[2] supports sending partial at both node and topic level.
        test.mockRouters[2].sendToSingle(controlExtensionsWithPartial())
        test.mockRouters[2].sendToSingle(subscribeRpc(requestsPartial = false, supportsSendingPartial = true))
        test.gossipRouter.submitOnEventThread {}.join()

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )
        test.fuzz.timeController.addTime(100.millis)

        // IDONTWANT IS sent because we never set requestsPartial=true locally.
        val iDontWantsToPartialPeer = test.mockRouters[2].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.idontwantList }
        assertThat(iDontWantsToPartialPeer).isNotEmpty()
    }

    @Test
    fun `IDONTWANT still sent when peer did not announce supportsSendingPartial for the topic`() {
        val test = startNetwork()

        // We request partial, but mockRouters[2] only announces node-level support
        // without a SubOpts supportsSendingPartial flag for this topic.
        test.gossipRouter.setTopicPartialFlags(topicId, requestsPartial = true, supportsSendingPartial = false)
        test.mockRouters[2].sendToSingle(controlExtensionsWithPartial())
        // No SubOpts with supportsSendingPartial=true — partialSubscriptionState has nothing.
        test.gossipRouter.submitOnEventThread {}.join()

        val msg = newMessage(topicId, 0L, "Hello".toByteArray())
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )
        test.fuzz.timeController.addTime(100.millis)

        // IDONTWANT IS sent because the peer has no topic-level supportsSendingPartial.
        val iDontWantsToPartialPeer = test.mockRouters[2].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.idontwantList }
        assertThat(iDontWantsToPartialPeer).isNotEmpty()
    }
}
