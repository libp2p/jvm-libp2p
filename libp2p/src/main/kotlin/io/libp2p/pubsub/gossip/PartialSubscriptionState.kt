package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic

data class PartialSubFlags(
    val requestsPartial: Boolean,
    val supportsSendingPartial: Boolean
) {
    companion object {
        val NONE = PartialSubFlags(requestsPartial = false, supportsSendingPartial = false)

        /**
         * Applies the partial-messages spec coercion
         * `supportsSendingPartial := requestsPartial || supportsSendingPartial`.
         *
         * Per the spec, this rule MUST be applied by both the sender (when
         * advertising flags outbound) and the receiver (when parsing inbound
         * `SubOpts`). Callers are expected to have already zeroed the flags
         * for `subscribe=false` frames before calling this helper.
         */
        fun coerce(requestsPartial: Boolean, supportsSendingPartial: Boolean): PartialSubFlags =
            PartialSubFlags(
                requestsPartial = requestsPartial,
                supportsSendingPartial = supportsSendingPartial || requestsPartial
            )
    }
}

/**
 * Per-topic, per-peer partial-messages subscription state.
 *
 * Tracks, for each `(topic, peer)`, the remote peer's `requestsPartial` /
 * `supportsSendingPartial` flags as most recently announced via a subscribe
 * `SubOpts`. Unsubscribes and peer disconnects drop the corresponding state.
 *
 * NOT thread-safe: accessed only on the pubsub event loop.
 */
class PartialSubscriptionState {

    private val byTopic: MutableMap<Topic, MutableMap<PeerId, PartialSubFlags>> = mutableMapOf()

    /**
     * Stores [flags] for `(topic, peer)`.
     *
     * Passing [PartialSubFlags.NONE] (or any equivalent `PartialSubFlags(false, false)`)
     * is treated as a removal: the peer's entry is dropped and, if it was the
     * last peer for the topic, the topic entry is GC'd. This keeps the snapshot
     * invariant "present ⇔ non-default flags".
     */
    fun setPeerFlags(topic: Topic, peer: PeerId, flags: PartialSubFlags) {
        if (flags == PartialSubFlags.NONE) {
            removePeerFlags(topic, peer)
            return
        }
        byTopic.getOrPut(topic) { mutableMapOf() }[peer] = flags
    }

    fun removePeerFlags(topic: Topic, peer: PeerId) {
        val peers = byTopic[topic] ?: return
        peers.remove(peer)
        if (peers.isEmpty()) byTopic.remove(topic)
    }

    fun removeTopic(topic: Topic) {
        byTopic.remove(topic)
    }

    fun onPeerDisconnected(peer: PeerId) {
        val emptied = mutableListOf<Topic>()
        for ((topic, peers) in byTopic) {
            peers.remove(peer)
            if (peers.isEmpty()) emptied += topic
        }
        emptied.forEach { byTopic.remove(it) }
    }

    fun peerFlags(topic: Topic, peer: PeerId): PartialSubFlags =
        byTopic[topic]?.get(peer) ?: PartialSubFlags.NONE

    fun peerRequestsPartial(topic: Topic, peer: PeerId) =
        peerFlags(topic, peer).requestsPartial

    fun peerSupportsSendingPartial(topic: Topic, peer: PeerId) =
        peerFlags(topic, peer).supportsSendingPartial

    internal fun snapshot(): Map<Topic, Map<PeerId, PartialSubFlags>> =
        byTopic.mapValues { (_, v) -> v.toMap() }
}
