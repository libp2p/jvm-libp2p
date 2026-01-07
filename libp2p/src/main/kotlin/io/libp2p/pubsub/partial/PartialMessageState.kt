package io.libp2p.pubsub.partial

import io.libp2p.core.PeerId
import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.toWBytes
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks partial message state per topic, per group, per peer.
 *
 * @param groupTtlHeartbeats TTL in heartbeats for group state cleanup
 * @param maxGroupsPerTopic Maximum peer-initiated groups per topic
 * @param maxGroupsPerTopicPerPeer Maximum peer-initiated groups per topic per peer
 */
class PartialMessageState(
    private val groupTtlHeartbeats: Int = 3,
    private val maxGroupsPerTopic: Int = 255,
    private val maxGroupsPerTopicPerPeer: Int = 8
) {
    // topic -> groupId -> GroupState
    private val state = ConcurrentHashMap<String, ConcurrentHashMap<WBytes, GroupState>>()

    // topic -> peerId -> set of groupIds initiated by that peer
    private val peerInitiatedGroups = ConcurrentHashMap<String, ConcurrentHashMap<PeerId, MutableSet<WBytes>>>()

    /**
     * Peer-specific state within a group.
     *
     * @property theirMetadata What metadata peer has sent us
     * @property ourSentMetadata What metadata we've sent to peer
     */
    data class PeerState(
        var theirMetadata: ByteArray? = null,
        var ourSentMetadata: ByteArray? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PeerState) return false
            return theirMetadata.contentEquals(other.theirMetadata) &&
                ourSentMetadata.contentEquals(other.ourSentMetadata)
        }

        override fun hashCode(): Int {
            var result = theirMetadata?.contentHashCode() ?: 0
            result = 31 * result + (ourSentMetadata?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * State for a partial message group.
     *
     * @property ttl Time-to-live in heartbeats
     * @property initiatedBy Peer that initiated this group, null if we initiated
     * @property peerStates Per-peer state within this group
     */
    data class GroupState(
        var ttl: Int,
        val initiatedBy: PeerId?,
        val peerStates: ConcurrentHashMap<PeerId, PeerState> = ConcurrentHashMap()
    )

    /**
     * Gets or creates state for a group.
     *
     * @param topic The topic name
     * @param groupId The group identifier
     * @param initiatedBy Peer that initiated this group, null if we initiated
     * @return GroupState, or null if rate limited
     */
    fun getOrCreateGroupState(
        topic: String,
        groupId: ByteArray,
        initiatedBy: PeerId?
    ): GroupState? {
        val topicState = state.computeIfAbsent(topic) { ConcurrentHashMap() }
        val groupIdW = groupId.toWBytes()

        val existing = topicState[groupIdW]
        if (existing != null) {
            existing.ttl = groupTtlHeartbeats // refresh TTL
            return existing
        }

        // Rate limiting for peer-initiated groups
        if (initiatedBy != null) {
            val topicPeerGroups = peerInitiatedGroups.computeIfAbsent(topic) { ConcurrentHashMap() }

            // Check per-topic limit
            val totalGroups = topicPeerGroups.values.sumOf { it.size }
            if (totalGroups >= maxGroupsPerTopic) return null

            // Check per-peer limit
            val peerGroups = topicPeerGroups.computeIfAbsent(initiatedBy) { mutableSetOf() }
            if (peerGroups.size >= maxGroupsPerTopicPerPeer) return null

            peerGroups.add(groupIdW)
        }

        val newState = GroupState(ttl = groupTtlHeartbeats, initiatedBy = initiatedBy)
        topicState[groupIdW] = newState
        return newState
    }

    /**
     * Gets existing state for a group.
     *
     * @param topic The topic name
     * @param groupId The group identifier
     * @return GroupState or null if not found
     */
    fun getGroupState(topic: String, groupId: ByteArray): GroupState? {
        return state[topic]?.get(groupId.toWBytes())
    }

    /**
     * Gets peer state within a group.
     *
     * @param topic The topic name
     * @param groupId The group identifier
     * @param peerId The peer identifier
     * @return PeerState or null if not found
     */
    fun getPeerState(topic: String, groupId: ByteArray, peerId: PeerId): PeerState? {
        return getGroupState(topic, groupId)?.peerStates?.get(peerId)
    }

    /**
     * Gets or creates peer state within a group.
     *
     * @param topic The topic name
     * @param groupId The group identifier
     * @param peerId The peer identifier
     * @return PeerState or null if group doesn't exist
     */
    fun getOrCreatePeerState(topic: String, groupId: ByteArray, peerId: PeerId): PeerState? {
        return getGroupState(topic, groupId)?.peerStates?.computeIfAbsent(peerId) { PeerState() }
    }

    /**
     * Updates peer's metadata for a group.
     *
     * @param topic The topic name
     * @param groupId The group identifier
     * @param peerId The peer identifier
     * @param metadata The metadata to merge
     * @param merger The merger to use for combining metadata
     * @return true if update succeeded, false if group doesn't exist
     */
    fun updatePeerMetadata(
        topic: String,
        groupId: ByteArray,
        peerId: PeerId,
        metadata: ByteArray,
        merger: PartsMetadataMerger
    ): Boolean {
        val peerState = getOrCreatePeerState(topic, groupId, peerId) ?: return false
        peerState.theirMetadata = merger.merge(peerState.theirMetadata, metadata)
        return true
    }

    /**
     * Called on heartbeat to decrement TTLs and clean up expired groups.
     */
    fun onHeartbeat() {
        state.forEach { (topic, topicState) ->
            val expired = topicState.filter { (_, groupState) ->
                groupState.ttl-- <= 0
            }.keys

            expired.forEach { groupId ->
                topicState.remove(groupId)
                // Clean up peer-initiated tracking
                peerInitiatedGroups[topic]?.values?.forEach { it.remove(groupId) }
            }
        }
    }

    /**
     * Called when peer disconnects to clean up their state.
     *
     * @param peerId The peer that disconnected
     */
    fun onPeerDisconnected(peerId: PeerId) {
        state.values.forEach { topicState ->
            topicState.values.forEach { groupState ->
                groupState.peerStates.remove(peerId)
            }
        }
        peerInitiatedGroups.values.forEach { it.remove(peerId) }
    }

    /**
     * Removes all state for a topic (on unsubscribe).
     *
     * @param topic The topic to remove
     */
    fun removeTopicState(topic: String) {
        state.remove(topic)
        peerInitiatedGroups.remove(topic)
    }
}
