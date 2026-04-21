package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic

data class PartialSubFlags(
    val requestsPartial: Boolean,
    val supportsSendingPartial: Boolean
) {
    companion object {
        val NONE = PartialSubFlags(requestsPartial = false, supportsSendingPartial = false)
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
