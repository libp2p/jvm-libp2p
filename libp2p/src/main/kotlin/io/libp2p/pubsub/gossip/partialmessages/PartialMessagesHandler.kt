package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc

/**
 * Client-supplied handler for the partial-messages extension.
 *
 * Both callbacks run on the pubsub event thread and MUST be fast and non-blocking.
 * Dispatch heavy work (decoding, KZG validation) to a separate executor.
 *
 * @param PeerState opaque per-(topic, groupId, peerId) state that the library
 *   stores and passes back; the library never interprets it.
 */
interface PartialMessagesHandler<PeerState> {

    /**
     * Called on every inbound [Rpc.PartialMessagesExtension] RPC.
     *
     * Any of [rpc].partialMessage and [rpc].partsMetadata may be absent; all
     * four combinations are valid wire messages.
     *
     * [peerStates] reflects the current state for this (topic, groupId) pair across
     * all peers. The map is a live view — do not retain a reference outside this call.
     *
     * Return the updated [PeerState] for [from], or null to leave it unchanged.
     *
     * **Ownership:** The returned [PeerState] instance is stored directly by the library.
     * If [PeerState] is a mutable type (e.g. a `ByteArray` or a mutable collection),
     * do not modify the returned instance after this method returns — doing so will
     * corrupt the library's stored state. Prefer immutable [PeerState] types.
     */
    fun onIncomingRpc(
        from: PeerId,
        peerStates: Map<PeerId, PeerState>,
        rpc: Rpc.PartialMessagesExtension,
        feedback: PartialMessagesPeerFeedback
    ): PeerState?

    /**
     * Called once per locally-initiated group during the gossipsub heartbeat for
     * gossip targets that are partial-capable on [topic].
     *
     * The client typically responds by calling [io.libp2p.pubsub.gossip.Gossip.publishPartial]
     * for the same (topic, groupId).
     *
     * [peerStates] reflects the current state for this group across all peers.
     * The map is a live view — do not retain a reference outside this call.
     * Element values in [peerStates] are the exact instances stored by the library;
     * do not mutate them.
     */
    fun onEmitGossip(
        topic: Topic,
        groupId: ByteArray,
        gossipPeers: Collection<PeerId>,
        peerStates: Map<PeerId, PeerState>,
        feedback: PartialMessagesPeerFeedback
    )
}
