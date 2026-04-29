package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PartialSubscriptionStateTest {

    private lateinit var state: PartialSubscriptionState
    private lateinit var peer1: PeerId
    private lateinit var peer2: PeerId
    private lateinit var peer3: PeerId

    private val topicA = "topic-a"
    private val topicB = "topic-b"

    @BeforeEach
    fun setup() {
        state = PartialSubscriptionState()
        peer1 = PeerId.random()
        peer2 = PeerId.random()
        peer3 = PeerId.random()
    }

    @Test
    fun `unknown peer returns NONE`() {
        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.peerRequestsPartial(topicA, peer1)).isFalse()
        assertThat(state.peerSupportsSendingPartial(topicA, peer1)).isFalse()
    }

    @Test
    fun `setPeerFlags stores and peerFlags reads back`() {
        val flags = PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        state.setPeerFlags(topicA, peer1, flags)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(flags)
        assertThat(state.peerRequestsPartial(topicA, peer1)).isTrue()
        assertThat(state.peerSupportsSendingPartial(topicA, peer1)).isTrue()
    }

    @Test
    fun `setPeerFlags with NONE removes entry`() {
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = false))
        state.setPeerFlags(topicA, peer1, PartialSubFlags.NONE)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.snapshot()).doesNotContainKey(topicA)
    }

    @Test
    fun `setPeerFlags overwrites previous flags for same peer and topic`() {
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = false, supportsSendingPartial = true))

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(
            PartialSubFlags(requestsPartial = false, supportsSendingPartial = true)
        )
    }

    @Test
    fun `removePeerFlags drops the peer's entry and GCs empty topic`() {
        val flags = PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        state.setPeerFlags(topicA, peer1, flags)

        state.removePeerFlags(topicA, peer1)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.snapshot()).doesNotContainKey(topicA)
    }

    @Test
    fun `removePeerFlags on unknown peer or topic is a no-op`() {
        state.removePeerFlags(topicA, peer1) // nothing stored yet
        assertThat(state.snapshot()).isEmpty()

        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
        state.removePeerFlags(topicB, peer1) // topic mismatch
        state.removePeerFlags(topicA, peer2) // peer mismatch

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(
            PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        )
    }

    @Test
    fun `removeTopic drops all peers for that topic, leaves other topics intact`() {
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
        state.setPeerFlags(topicA, peer2, PartialSubFlags(requestsPartial = false, supportsSendingPartial = true))
        state.setPeerFlags(topicB, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        state.removeTopic(topicA)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.peerFlags(topicA, peer2)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.peerFlags(topicB, peer1)).isEqualTo(
            PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        )
    }

    @Test
    fun `onPeerDisconnected clears the peer across all topics, leaves other peers intact`() {
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
        state.setPeerFlags(topicA, peer2, PartialSubFlags(requestsPartial = false, supportsSendingPartial = true))
        state.setPeerFlags(topicB, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
        state.setPeerFlags(topicB, peer3, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        state.onPeerDisconnected(peer1)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.peerFlags(topicB, peer1)).isEqualTo(PartialSubFlags.NONE)
        assertThat(state.peerFlags(topicA, peer2)).isEqualTo(
            PartialSubFlags(requestsPartial = false, supportsSendingPartial = true)
        )
        assertThat(state.peerFlags(topicB, peer3)).isEqualTo(
            PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        )
    }

    @Test
    fun `onPeerDisconnected GCs topics that become empty`() {
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))
        state.setPeerFlags(topicB, peer2, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        state.onPeerDisconnected(peer1)

        assertThat(state.snapshot()).doesNotContainKey(topicA)
        assertThat(state.snapshot()).containsKey(topicB)
    }

    @Test
    fun `onPeerDisconnected on unknown peer is a no-op`() {
        state.setPeerFlags(topicA, peer1, PartialSubFlags(requestsPartial = true, supportsSendingPartial = true))

        state.onPeerDisconnected(peer2)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(
            PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        )
    }

    @Test
    fun `peer independence on same topic`() {
        val flags1 = PartialSubFlags(requestsPartial = true, supportsSendingPartial = true)
        val flags2 = PartialSubFlags(requestsPartial = false, supportsSendingPartial = true)
        state.setPeerFlags(topicA, peer1, flags1)
        state.setPeerFlags(topicA, peer2, flags2)

        assertThat(state.peerFlags(topicA, peer1)).isEqualTo(flags1)
        assertThat(state.peerFlags(topicA, peer2)).isEqualTo(flags2)
    }
}
