package io.libp2p.pubsub.partial

import io.libp2p.core.PeerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PartialMessageStateTest {
    private val peerId1 = PeerId.random()
    private val peerId2 = PeerId.random()
    private val topic = "test-topic"
    private val groupId = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `should create and retrieve group state`() {
        val state = PartialMessageState()

        val groupState = state.getOrCreateGroupState(topic, groupId, null)
        assertThat(groupState).isNotNull

        val retrieved = state.getGroupState(topic, groupId)
        assertThat(retrieved).isSameAs(groupState)
    }

    @Test
    fun `should track peer state within group`() {
        val state = PartialMessageState()
        state.getOrCreateGroupState(topic, groupId, null)

        val peerState = state.getOrCreatePeerState(topic, groupId, peerId1)
        assertThat(peerState).isNotNull
        assertThat(peerState!!.theirMetadata).isNull()
    }

    @Test
    fun `should update peer metadata`() {
        val state = PartialMessageState()
        state.getOrCreateGroupState(topic, groupId, null)

        val metadata = byteArrayOf(0b00001111)
        state.updatePeerMetadata(topic, groupId, peerId1, metadata, BitwiseOrMerger)

        val peerState = state.getPeerState(topic, groupId, peerId1)
        assertThat(peerState?.theirMetadata).isEqualTo(metadata)
    }

    @Test
    fun `should merge metadata with bitwise OR`() {
        val state = PartialMessageState()
        state.getOrCreateGroupState(topic, groupId, null)

        state.updatePeerMetadata(topic, groupId, peerId1, byteArrayOf(0b00001111), BitwiseOrMerger)
        state.updatePeerMetadata(topic, groupId, peerId1, byteArrayOf(0b11110000.toByte()), BitwiseOrMerger)

        val peerState = state.getPeerState(topic, groupId, peerId1)
        assertThat(peerState?.theirMetadata).isEqualTo(byteArrayOf(0b11111111.toByte()))
    }

    @Test
    fun `should expire groups on heartbeat`() {
        val state = PartialMessageState(groupTtlHeartbeats = 2)
        state.getOrCreateGroupState(topic, groupId, null)

        assertThat(state.getGroupState(topic, groupId)).isNotNull

        state.onHeartbeat() // TTL 2 -> 1
        assertThat(state.getGroupState(topic, groupId)).isNotNull

        state.onHeartbeat() // TTL 1 -> 0
        assertThat(state.getGroupState(topic, groupId)).isNotNull

        state.onHeartbeat() // TTL 0 -> expired
        assertThat(state.getGroupState(topic, groupId)).isNull()
    }

    @Test
    fun `should rate limit peer-initiated groups`() {
        val state = PartialMessageState(maxGroupsPerTopicPerPeer = 2)

        val group1 = state.getOrCreateGroupState(topic, byteArrayOf(1), peerId1)
        val group2 = state.getOrCreateGroupState(topic, byteArrayOf(2), peerId1)
        val group3 = state.getOrCreateGroupState(topic, byteArrayOf(3), peerId1)

        assertThat(group1).isNotNull
        assertThat(group2).isNotNull
        assertThat(group3).isNull() // Rate limited
    }

    @Test
    fun `should clean up on peer disconnect`() {
        val state = PartialMessageState()
        state.getOrCreateGroupState(topic, groupId, null)
        state.getOrCreatePeerState(topic, groupId, peerId1)
        state.updatePeerMetadata(topic, groupId, peerId1, byteArrayOf(1), BitwiseOrMerger)

        state.onPeerDisconnected(peerId1)

        assertThat(state.getPeerState(topic, groupId, peerId1)).isNull()
    }
}
