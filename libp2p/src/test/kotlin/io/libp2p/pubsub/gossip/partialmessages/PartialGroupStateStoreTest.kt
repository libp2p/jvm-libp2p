package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PartialGroupStateStoreTest {

    private lateinit var store: PartialGroupStateStore<String>
    private lateinit var peer1: PeerId
    private lateinit var peer2: PeerId

    private val topicA = "topic-a"
    private val topicB = "topic-b"
    private val groupId1 = "group-1".toByteArray().toGroupId()
    private val groupId2 = "group-2".toByteArray().toGroupId()

    @BeforeEach
    fun setup() {
        store = PartialGroupStateStore(groupTtlHeartbeats = 3)
        peer1 = PeerId.random()
        peer2 = PeerId.random()
    }

    // --- GroupId equality ---

    @Test
    fun `GroupId equality is content-based`() {
        val a = "abc".toByteArray().toGroupId()
        val b = "abc".toByteArray().toGroupId()
        val c = "xyz".toByteArray().toGroupId()
        assertThat(a).isEqualTo(b)
        assertThat(a).isNotEqualTo(c)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `GroupId works as HashMap key`() {
        val map = HashMap<GroupId, Int>()
        map["abc".toByteArray().toGroupId()] = 42
        assertThat(map["abc".toByteArray().toGroupId()]).isEqualTo(42)
    }

    @Test
    fun `GroupId is immune to mutation of original ByteArray`() {
        val original = "abc".toByteArray()
        val gid = original.toGroupId()
        val map = HashMap<GroupId, Int>()
        map[gid] = 42

        // mutate the original after inserting into the map
        original[0] = 'z'.code.toByte()

        // lookup by a fresh GroupId with the original bytes should still work
        assertThat(map["abc".toByteArray().toGroupId()]).isEqualTo(42)
        // the stored GroupId should not reflect the mutation
        assertThat(gid).isEqualTo("abc".toByteArray().toGroupId())
    }

    // --- local groups ---

    @Test
    fun `getOrCreateLocalGroup creates a new group`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        assertThat(group.peerInitiated).isFalse()
        assertThat(group.initiatingPeer).isNull()
        assertThat(group.ttlInHeartbeats).isEqualTo(3)
        assertThat(store.getGroup(topicA, groupId1)).isSameAs(group)
    }

    @Test
    fun `getOrCreateLocalGroup resets TTL on existing group`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.ttlInHeartbeats = 1
        store.getOrCreateLocalGroup(topicA, groupId1)
        assertThat(group.ttlInHeartbeats).isEqualTo(3)
    }

    @Test
    fun `getOrCreateLocalGroup returns same object on repeated calls`() {
        val g1 = store.getOrCreateLocalGroup(topicA, groupId1)
        val g2 = store.getOrCreateLocalGroup(topicA, groupId1)
        assertThat(g1).isSameAs(g2)
    }

    // --- peer groups ---

    @Test
    fun `getOrCreatePeerGroup creates a peer-initiated group`() {
        val group = store.getOrCreatePeerGroup(topicA, groupId1, peer1)
        assertThat(group).isNotNull()
        assertThat(group!!.peerInitiated).isTrue()
        assertThat(group.initiatingPeer).isEqualTo(peer1)
        assertThat(group.ttlInHeartbeats).isEqualTo(3)
    }

    @Test
    fun `getOrCreatePeerGroup returns existing group`() {
        val g1 = store.getOrCreatePeerGroup(topicA, groupId1, peer1)
        val g2 = store.getOrCreatePeerGroup(topicA, groupId1, peer1)
        assertThat(g1).isSameAs(g2)
    }

    @Test
    fun `per-topic cap rejects new peer-initiated groups`() {
        val smallCapStore = PartialGroupStateStore<String>(
            peerInitiatedGroupLimitPerTopic = 2
        )
        val g1id = "g1".toByteArray().toGroupId()
        val g2id = "g2".toByteArray().toGroupId()
        val g3id = "g3".toByteArray().toGroupId()

        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g1id, peer1)).isNotNull()
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g2id, peer1)).isNotNull()
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g3id, peer1)).isNull()
    }

    @Test
    fun `per-topic cap does not count local-initiated groups`() {
        val smallCapStore = PartialGroupStateStore<String>(
            peerInitiatedGroupLimitPerTopic = 1
        )
        smallCapStore.getOrCreateLocalGroup(topicA, "local1".toByteArray().toGroupId())
        smallCapStore.getOrCreateLocalGroup(topicA, "local2".toByteArray().toGroupId())

        // Only 0 peer-initiated groups, so cap not reached
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, groupId1, peer1)).isNotNull()
        // Now cap reached (1 peer-initiated)
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, groupId2, peer2)).isNull()
    }

    @Test
    fun `per-peer cap rejects new peer-initiated groups for that peer`() {
        val smallCapStore = PartialGroupStateStore<String>(
            peerInitiatedGroupLimitPerTopicPerPeer = 2
        )
        val g1id = "g1".toByteArray().toGroupId()
        val g2id = "g2".toByteArray().toGroupId()
        val g3id = "g3".toByteArray().toGroupId()

        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g1id, peer1)).isNotNull()
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g2id, peer1)).isNotNull()
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g3id, peer1)).isNull()

        // peer2 should still be allowed (different peer)
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, g3id, peer2)).isNotNull()
    }

    @Test
    fun `per-peer cap is per-topic — other topics are unaffected`() {
        val smallCapStore = PartialGroupStateStore<String>(
            peerInitiatedGroupLimitPerTopicPerPeer = 1
        )
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, groupId1, peer1)).isNotNull()
        assertThat(smallCapStore.getOrCreatePeerGroup(topicA, groupId2, peer1)).isNull()
        assertThat(smallCapStore.getOrCreatePeerGroup(topicB, groupId1, peer1)).isNotNull()
    }

    // --- TTL and heartbeat GC ---

    @Test
    fun `onHeartbeat decrements TTL`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.peerStates[peer1] = "state" // prevent GC by empty-peerStates rule
        store.onHeartbeat()
        assertThat(group.ttlInHeartbeats).isEqualTo(2)
    }

    @Test
    fun `onHeartbeat removes group when TTL reaches zero`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.peerStates[peer1] = "state" // prevent GC by empty-peerStates rule
        repeat(3) { store.onHeartbeat() }
        assertThat(store.getGroup(topicA, groupId1)).isNull()
    }

    @Test
    fun `onHeartbeat removes group when peerStates is empty`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.peerStates[peer1] = "state"
        group.peerStates.remove(peer1)
        store.onHeartbeat()
        assertThat(store.getGroup(topicA, groupId1)).isNull()
    }

    @Test
    fun `onHeartbeat does not remove group with non-empty peerStates before TTL`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.peerStates[peer1] = "state"
        store.onHeartbeat()
        assertThat(store.getGroup(topicA, groupId1)).isSameAs(group)
    }

    @Test
    fun `resetTtl refreshes TTL for a group`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.peerStates[peer1] = "state" // prevent GC by empty-peerStates rule
        repeat(2) { store.onHeartbeat() }
        assertThat(group.ttlInHeartbeats).isEqualTo(1)
        store.resetTtl(topicA, groupId1)
        assertThat(group.ttlInHeartbeats).isEqualTo(3)
    }

    // --- peer disconnect ---

    @Test
    fun `onPeerDisconnected removes peer from all group peerStates`() {
        val group1 = store.getOrCreateLocalGroup(topicA, groupId1)
        val group2 = store.getOrCreateLocalGroup(topicB, groupId2)
        group1.peerStates[peer1] = "state1"
        group1.peerStates[peer2] = "state2"
        group2.peerStates[peer1] = "state3"

        store.onPeerDisconnected(peer1)

        assertThat(group1.peerStates).containsOnlyKeys(peer2)
        assertThat(group2.peerStates).isEmpty()
    }

    @Test
    fun `onPeerDisconnected GCs groups whose peerStates become empty`() {
        val group = store.getOrCreateLocalGroup(topicA, groupId1)
        group.peerStates[peer1] = "only-state"

        store.onPeerDisconnected(peer1)

        assertThat(store.getGroup(topicA, groupId1)).isNull()
        assertThat(store.groupsForTopic(topicA)).isEmpty()
    }

    // --- topic unsubscribe ---

    @Test
    fun `onTopicUnsubscribed removes all groups for that topic`() {
        store.getOrCreateLocalGroup(topicA, groupId1)
        store.getOrCreateLocalGroup(topicA, groupId2)
        store.getOrCreateLocalGroup(topicB, groupId1)

        store.onTopicUnsubscribed(topicA)

        assertThat(store.groupsForTopic(topicA)).isEmpty()
        assertThat(store.groupsForTopic(topicB)).isNotEmpty()
    }

    // --- groupsForTopic ---

    @Test
    fun `groupsForTopic returns empty map for unknown topic`() {
        assertThat(store.groupsForTopic("unknown-topic")).isEmpty()
    }

    @Test
    fun `groupsForTopic returns all groups for the topic`() {
        store.getOrCreateLocalGroup(topicA, groupId1)
        store.getOrCreatePeerGroup(topicA, groupId2, peer1)

        assertThat(store.groupsForTopic(topicA)).hasSize(2)
        assertThat(store.groupsForTopic(topicB)).isEmpty()
    }
}
