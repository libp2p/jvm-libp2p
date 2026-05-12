package io.libp2p.pubsub.gossip.extensions

import io.libp2p.core.PeerId
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipTestsBase
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Tests for Step 9 — IHAVE replacement with onEmitGossip (§5.3).
 *
 * When we support sending partial messages for a topic and a peer has requested
 * partial delivery, the gossip router MUST NOT send IHAVE to that peer during the
 * heartbeat lazy-push. Instead, the router calls handler.onEmitGossip once per
 * locally-initiated group under that topic.
 */
class PartialMessagesEmitGossipTest : GossipTestsBase() {

    private val topicId = "test-topic"
    private val groupIdBytes = byteArrayOf(1, 2, 3)

    // D=0 keeps all mock routers out of the mesh, making them IHAVE gossip candidates.
    // DLazy=6 ensures all eligible peers are selected in the lazy-push.
    // floodPublishMaxMessageSizeThreshold=0 disables flood publish so IHAVE is the
    // sole mechanism for notifying non-mesh peers about messages.
    private val testParams = GossipParams(
        D = 0,
        DLow = 0,
        DHigh = 0,
        DLazy = 6,
        floodPublishMaxMessageSizeThreshold = 0,
    )

    private fun controlExtensionsWithPartial(): Rpc.RPC =
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().setExtensions(
                Rpc.ControlExtensions.newBuilder().setPartialMessages(true)
            )
        ).build()

    private fun subscribeRpc(
        requestsPartial: Boolean,
        supportsSendingPartial: Boolean,
    ): Rpc.RPC =
        Rpc.RPC.newBuilder().addSubscriptions(
            Rpc.RPC.SubOpts.newBuilder()
                .setTopicid(topicId)
                .setSubscribe(true)
                .setRequestsPartial(requestsPartial)
                .setSupportsSendingPartial(supportsSendingPartial)
        ).build()

    /**
     * Creates a 2-mock-router test network where:
     * - mockRouters[0] is a plain non-partial peer
     * - mockRouters[1] is a partial-capable peer that has requested partial delivery
     * - gossipRouter supports sending partial for [topicId]
     * - D=0 so no mesh, all peers are lazy-push (IHAVE) candidates
     */
    private fun startNetwork(
        handler: PartialMessagesHandler<*> = nopPartialMessagesHandler,
    ): ManyRoutersTest {
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = testParams,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = handler,
        )
        test.connectAll()
        test.mockRouters.forEach { it.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)
        test.gossipRouter.setTopicPartialFlags(
            topicId,
            requestsPartial = false,
            supportsSendingPartial = true,
        )

        // mockRouters[1] is the partial peer: node-level support + topic-level request.
        test.mockRouters[1].sendToSingle(controlExtensionsWithPartial())
        test.mockRouters[1].sendToSingle(subscribeRpc(requestsPartial = true, supportsSendingPartial = true))

        test.gossipRouter.submitOnEventThread {}.join()
        return test
    }

    // ── IHAVE suppression ────────────────────────────────────────────────────

    @Test
    fun `IHAVE not sent to partial peer when we support sending partial`() {
        val test = startNetwork()

        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        // Non-partial peer MUST receive IHAVE.
        val ihaveToNormal = test.mockRouters[0].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.ihaveList }
        assertThat(ihaveToNormal).isNotEmpty()

        // Partial peer MUST NOT receive IHAVE.
        val ihaveToPartial = test.mockRouters[1].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.ihaveList }
        assertThat(ihaveToPartial).isEmpty()
    }

    @Test
    fun `IHAVE still sent to partial peer when partial extension disabled`() {
        val test = ManyRoutersTest(
            mockRouterCount = 1,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = testParams,
        )
        test.connectAll()
        test.mockRouters[0].subscribe(topicId)
        test.gossipRouter.subscribe(topicId)
        test.gossipRouter.submitOnEventThread {}.join()

        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        val ihaves = test.mockRouters[0].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.ihaveList }
        assertThat(ihaves).isNotEmpty()
    }

    @Test
    fun `IHAVE still sent to partial peer when we do not support sending partial for the topic`() {
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = testParams,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = nopPartialMessagesHandler,
        )
        test.connectAll()
        test.mockRouters.forEach { it.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)
        // We do NOT call setTopicPartialFlags — supportsSendingPartial stays false.
        test.mockRouters[1].sendToSingle(controlExtensionsWithPartial())
        test.mockRouters[1].sendToSingle(subscribeRpc(requestsPartial = true, supportsSendingPartial = true))
        test.gossipRouter.submitOnEventThread {}.join()

        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        // IHAVE IS sent because we don't support sending partial for this topic.
        val ihaveToPartialPeer = test.mockRouters[1].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.ihaveList }
        assertThat(ihaveToPartialPeer).isNotEmpty()
    }

    @Test
    fun `IHAVE still sent when peer supports partial but did not request it`() {
        val test = ManyRoutersTest(
            mockRouterCount = 2,
            protocol = PubsubProtocol.Gossip_V_1_3,
            params = testParams,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = nopPartialMessagesHandler,
        )
        test.connectAll()
        test.mockRouters.forEach { it.subscribe(topicId) }
        test.gossipRouter.subscribe(topicId)
        test.gossipRouter.setTopicPartialFlags(
            topicId,
            requestsPartial = false,
            supportsSendingPartial = true,
        )
        // mockRouters[1] supports sending partial but did NOT request it.
        test.mockRouters[1].sendToSingle(controlExtensionsWithPartial())
        test.mockRouters[1].sendToSingle(subscribeRpc(requestsPartial = false, supportsSendingPartial = true))
        test.gossipRouter.submitOnEventThread {}.join()

        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        // IHAVE IS sent because the peer didn't request partial.
        val ihaveToPartialPeer = test.mockRouters[1].inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.ihaveList }
        assertThat(ihaveToPartialPeer).isNotEmpty()
    }

    // ── onEmitGossip callback ─────────────────────────────────────────────────

    @Test
    fun `onEmitGossip called for locally-initiated group with partial-capable gossip peers`() {
        data class Call(val topic: Topic, val groupId: ByteArray, val peers: List<PeerId>)
        val calls = mutableListOf<Call>()

        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ): Unit? = null

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>,
                feedback: PartialMessagesPeerFeedback,
            ) {
                calls += Call(topic, groupId, gossipPeers.toList())
            }
        }

        val test = startNetwork(handler)
        val partialPeerId = test.routers[1].peerId

        // Create a locally-initiated group via publishPartial.
        test.gossipRouter.publishPartial(topicId, groupIdBytes, PublishActionsFn<Unit> { _, _ -> emptySequence() })

        // Publish a message to put an entry in the mcache; emitGossip only runs when
        // mcache is non-empty.
        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))

        test.fuzz.timeController.addTime(2.seconds)

        assertThat(calls).isNotEmpty()
        val call = calls.first { it.topic == topicId && it.groupId.contentEquals(groupIdBytes) }
        assertThat(call.peers).contains(partialPeerId)
    }

    @Test
    fun `onEmitGossip not called for peer-initiated groups`() {
        data class Call(val topic: Topic, val groupId: ByteArray)
        val calls = mutableListOf<Call>()

        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ): Unit? = null

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>,
                feedback: PartialMessagesPeerFeedback,
            ) {
                calls += Call(topic, groupId)
            }
        }

        val test = startNetwork(handler)
        val peerGroupId = byteArrayOf(9, 8, 7)

        // Simulate an inbound partial RPC that creates a peer-initiated group.
        val inboundRpc = Rpc.RPC.newBuilder().setPartial(
            Rpc.PartialMessagesExtension.newBuilder()
                .setTopicID(topicId)
                .setGroupID(com.google.protobuf.ByteString.copyFrom(peerGroupId))
                .setPartsMetadata(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01)))
        ).build()
        test.mockRouters[1].sendToSingle(inboundRpc)
        test.gossipRouter.submitOnEventThread {}.join()

        // There are no locally-initiated groups; only a peer-initiated one.
        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        // handler.onEmitGossip MUST NOT be called for the peer-initiated group.
        assertThat(calls.filter { it.groupId.contentEquals(peerGroupId) }).isEmpty()
    }

    @Test
    fun `peer-initiated group that is also locally published triggers onEmitGossip`() {
        data class Call(val topic: Topic, val groupId: ByteArray, val peers: List<PeerId>)
        val calls = mutableListOf<Call>()

        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ): Unit? = null

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>,
                feedback: PartialMessagesPeerFeedback,
            ) {
                calls += Call(topic, groupId, gossipPeers.toList())
            }
        }

        val test = startNetwork(handler)
        val peerGroupId = byteArrayOf(9, 8, 7)
        val partialPeerId = test.routers[1].peerId

        // Step 1: receive an inbound partial RPC that creates a peer-initiated group.
        val inboundRpc = Rpc.RPC.newBuilder().setPartial(
            Rpc.PartialMessagesExtension.newBuilder()
                .setTopicID(topicId)
                .setGroupID(com.google.protobuf.ByteString.copyFrom(peerGroupId))
                .setPartsMetadata(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01)))
        ).build()
        test.mockRouters[1].sendToSingle(inboundRpc)
        test.gossipRouter.submitOnEventThread {}.join()

        // Step 2: local application also publishes to the same group, marking it locally published.
        test.gossipRouter.publishPartial(topicId, peerGroupId, PublishActionsFn<Unit> { _, _ -> emptySequence() })

        // Step 3: trigger a heartbeat; emitGossip requires a non-empty mcache.
        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        // onEmitGossip MUST be called for the group because it is also locally published.
        val matchingCalls = calls.filter { it.topic == topicId && it.groupId.contentEquals(peerGroupId) }
        assertThat(matchingCalls).isNotEmpty()
        assertThat(matchingCalls.first().peers).contains(partialPeerId)
    }

    @Test
    fun `onEmitGossip not called when there are no locally-initiated groups`() {
        val calls = mutableListOf<Unit>()

        val handler = object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ): Unit? = null

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>,
                feedback: PartialMessagesPeerFeedback,
            ) {
                calls += Unit
            }
        }

        val test = startNetwork(handler)

        // No publishPartial call → no locally-initiated groups exist.
        test.gossipRouter.publish(newMessage(topicId, 0L, "data".toByteArray()))
        test.fuzz.timeController.addTime(2.seconds)

        assertThat(calls).isEmpty()
    }
}
