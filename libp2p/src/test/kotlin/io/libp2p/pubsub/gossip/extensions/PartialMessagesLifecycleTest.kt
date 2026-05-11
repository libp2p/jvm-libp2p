package io.libp2p.pubsub.gossip.extensions

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipTestsBase
import io.libp2p.pubsub.gossip.partialmessages.PartialGroupStateStore
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesAdapterImpl
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn
import io.libp2p.pubsub.gossip.partialmessages.toGroupId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Tests for Step 8 — heartbeat tick + TTL GC + cleanup hooks (§6.4).
 *
 * Verifies that the three wiring points added in GossipRouter actually invoke
 * the partial-messages adapter at the right times:
 * - heartbeat → TTL decrement and GC of expired groups
 * - onPeerDisconnected → peer state removed from all groups
 * - unsubscribe → all group state for the topic dropped
 */
class PartialMessagesLifecycleTest : GossipTestsBase() {

    private val topicId = "test-topic"
    private val groupId = "group-1".toByteArray()

    private fun newTest() =
        TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = nopPartialMessagesHandler,
        )

    @Suppress("UNCHECKED_CAST")
    private fun TwoRoutersTest.store(): PartialGroupStateStore<Any?> =
        (gossipRouter.partialMessages as PartialMessagesAdapterImpl<Any?>).stateStore

    /**
     * Seeds a group with one peer-state entry so peerStates is non-empty and
     * the group survives heartbeats until its TTL expires (not GC'd immediately
     * by the peerStates-empty condition).
     */
    private fun TwoRoutersTest.seedGroup(peer: PeerId) {
        gossipRouter.submitOnEventThread {
            val group = store().getOrCreateLocalGroup(topicId, groupId.toGroupId())
            group.peerStates[peer] = "sentinel"
        }.join()
    }

    private fun controlExtensionsWithPartial(): Rpc.RPC =
        Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .setExtensions(Rpc.ControlExtensions.newBuilder().setPartialMessages(true))
            )
            .build()

    private fun partialRpc(): Rpc.RPC =
        Rpc.RPC.newBuilder()
            .setPartial(
                Rpc.PartialMessagesExtension.newBuilder()
                    .setTopicID(topicId)
                    .setGroupID(ByteString.copyFrom(groupId))
            )
            .build()

    // ── Heartbeat GC ──────────────────────────────────────────────────────────

    @Test
    fun `heartbeat GCs peer-initiated group whose peerStates is empty`() {
        val test = newTest()

        // Peer-initiated group via inbound RPC; nopHandler sets no peerStates → empty
        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpc())
        test.gossipRouter.submitOnEventThread {}.join()

        val store = test.store()
        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNotNull()

        // One heartbeat fires; peerStates.isEmpty() triggers immediate GC
        test.fuzz.timeController.addTime(2.seconds)

        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNull()
    }

    @Test
    fun `heartbeat decrements TTL and GCs group after TTL expires`() {
        val test = newTest()
        val mockPeerId = test.router2.peerId
        test.seedGroup(mockPeerId)

        val store = test.store()
        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNotNull()

        // DEFAULT_GROUP_TTL_HEARTBEATS = 5; advance 6 s to fire 6 heartbeats
        test.fuzz.timeController.addTime(6.seconds)

        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNull()
    }

    @Test
    fun `publishPartial resets TTL so group survives past initial TTL`() {
        val test = newTest()
        val mockPeerId = test.router2.peerId
        test.seedGroup(mockPeerId)

        val store = test.store()

        // Advance 4 heartbeats: TTL goes 5→4→3→2→1; group still alive
        test.fuzz.timeController.addTime(4.seconds)
        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNotNull()

        // publishPartial with empty actions still calls getOrCreateLocalGroup → resets TTL
        test.gossipRouter.publishPartial(topicId, groupId, PublishActionsFn<Any?> { _, _ -> emptySequence() })

        // Advance 4 more heartbeats: TTL goes 5→4→3→2→1; group still alive
        test.fuzz.timeController.addTime(4.seconds)
        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNotNull()
    }

    // ── Peer disconnect ───────────────────────────────────────────────────────

    @Test
    fun `peer disconnect removes peer from group state and GCs now-empty group`() {
        val test = newTest()
        val mockPeerId = test.router2.peerId
        test.seedGroup(mockPeerId)

        val store = test.store()
        assertThat(store.getGroup(topicId, groupId.toGroupId())?.peerStates).containsKey(mockPeerId)

        test.connection.disconnect()
        test.gossipRouter.submitOnEventThread {}.join()

        // peerStates is now empty → group GC'd immediately in onPeerDisconnected
        assertThat(store.getGroup(topicId, groupId.toGroupId())).isNull()
    }

    @Test
    fun `peer disconnect leaves group alive when other peers still have state`() {
        val test = newTest()
        val mockPeerId = test.router2.peerId
        val otherPeerId = PeerId.random()

        test.gossipRouter.submitOnEventThread {
            val group = test.store().getOrCreateLocalGroup(topicId, groupId.toGroupId())
            group.peerStates[mockPeerId] = "mock-state"
            group.peerStates[otherPeerId] = "other-state"
        }.join()

        test.connection.disconnect()
        test.gossipRouter.submitOnEventThread {}.join()

        // Group survives because otherPeerId still has state
        val group = test.store().getGroup(topicId, groupId.toGroupId())
        assertThat(group).isNotNull()
        assertThat(group?.peerStates).doesNotContainKey(mockPeerId)
        assertThat(group?.peerStates).containsKey(otherPeerId)
    }

    // ── Unsubscribe ───────────────────────────────────────────────────────────

    @Test
    fun `unsubscribing from topic drops all group state for that topic`() {
        val test = newTest()
        test.gossipRouter.subscribe(topicId)
        val mockPeerId = test.router2.peerId
        test.seedGroup(mockPeerId)

        val store = test.store()
        assertThat(store.groupsForTopic(topicId)).isNotEmpty()

        test.gossipRouter.unsubscribe(topicId)
        test.gossipRouter.submitOnEventThread {}.join()

        assertThat(store.groupsForTopic(topicId)).isEmpty()
    }

    @Test
    fun `unsubscribing from one topic does not affect groups on other topics`() {
        val otherTopic = "other-topic"
        val test = newTest()
        test.gossipRouter.subscribe(topicId)
        val mockPeerId = test.router2.peerId

        test.gossipRouter.submitOnEventThread {
            val store = test.store()
            store.getOrCreateLocalGroup(topicId, groupId.toGroupId()).peerStates[mockPeerId] = "s1"
            store.getOrCreateLocalGroup(otherTopic, groupId.toGroupId()).peerStates[mockPeerId] = "s2"
        }.join()

        test.gossipRouter.unsubscribe(topicId)
        test.gossipRouter.submitOnEventThread {}.join()

        assertThat(test.store().groupsForTopic(topicId)).isEmpty()
        assertThat(test.store().groupsForTopic(otherTopic)).isNotEmpty()
    }
}
