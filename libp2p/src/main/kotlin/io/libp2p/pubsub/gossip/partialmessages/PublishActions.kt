package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId

/**
 * Encodes what the library should send to one peer for a single
 * [io.libp2p.pubsub.gossip.Gossip.publishPartial] call.
 *
 * [nextPeerState] is applied atomically by the library per peer after the
 * send; null means "leave the existing state unchanged".
 *
 * **Ownership:** The [nextPeerState] instance is stored directly by the library.
 * Prefer immutable types; do not mutate [nextPeerState] after returning it from [PublishActionsFn.decide].
 */
data class PublishAction<PeerState>(
    val partialMessage: ByteArray? = null,
    val partsMetadata: ByteArray? = null,
    val nextPeerState: PeerState? = null,
    val error: Throwable? = null
)

/**
 * Decision function supplied by the client to [io.libp2p.pubsub.gossip.Gossip.publishPartial].
 *
 * [decide] is called on the pubsub event thread with the current peer state map
 * and a predicate for checking whether a peer requested partial for the topic.
 * It must return a sequence of (peerId, action) pairs — one per peer that
 * should receive an outbound [pubsub.pb.Rpc.PartialMessagesExtension] RPC.
 */
fun interface PublishActionsFn<PeerState> {
    fun decide(
        peerStates: Map<PeerId, PeerState>,
        peerRequestsPartial: (PeerId) -> Boolean
    ): Sequence<Pair<PeerId, PublishAction<PeerState>>>
}
