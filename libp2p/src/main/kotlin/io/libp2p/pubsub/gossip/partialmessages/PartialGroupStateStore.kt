package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(PartialGroupStateStore::class.java)

const val DEFAULT_GROUP_TTL_HEARTBEATS = 5
const val DEFAULT_PEER_INITIATED_GROUP_LIMIT_PER_TOPIC = 255
const val DEFAULT_PEER_INITIATED_GROUP_LIMIT_PER_TOPIC_PER_PEER = 8

/**
 * Stable, value-based identity for a partial-messages group ID.
 *
 * Wraps a raw [ByteArray] so it can be used as a [HashMap] key with
 * content equality rather than reference equality.
 */
class GroupId(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()
    override fun equals(other: Any?): Boolean =
        other is GroupId && this.bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }
}

fun ByteArray.toGroupId(): GroupId = GroupId(this)

/**
 * Per-(topic, groupId) state container.
 *
 * [peerStates] is mutable and updated as parts arrive.
 * [ttlInHeartbeats] is decremented each heartbeat and reset on [PartialGroupStateStore.resetTtl].
 * [firstSeenFromPeer] is non-null iff [peerInitiated] is true.
 *
 * NOT thread-safe: accessed only on the pubsub event loop.
 */
class GroupState<PeerState>(
    var ttlInHeartbeats: Int,
    val peerInitiated: Boolean,
    /**
     * The peer whose inbound RPC first created this entry in the local store.
     * Non-null iff [peerInitiated] is true. Note: subsequent peers contributing
     * to the same group reuse this entry without updating this field — the
     * per-peer DoS cap is only applied at creation time.
     */
    val firstSeenFromPeer: PeerId?,
    var locallyPublished: Boolean = false
) {
    val peerStates: MutableMap<PeerId, PeerState> = mutableMapOf()
}

/**
 * Stores and manages per-(topic, groupId) [GroupState] entries for the partial-messages
 * extension.
 *
 * DoS caps (matching go-libp2p defaults):
 * - [peerInitiatedGroupLimitPerTopic]: max peer-initiated groups across all peers per topic.
 * - [peerInitiatedGroupLimitPerTopicPerPeer]: max peer-initiated groups per (topic, peer).
 *
 * NOT thread-safe: all access must be serialised on the pubsub event loop.
 */
class PartialGroupStateStore<PeerState>(
    val groupTtlHeartbeats: Int = DEFAULT_GROUP_TTL_HEARTBEATS,
    val peerInitiatedGroupLimitPerTopic: Int = DEFAULT_PEER_INITIATED_GROUP_LIMIT_PER_TOPIC,
    val peerInitiatedGroupLimitPerTopicPerPeer: Int = DEFAULT_PEER_INITIATED_GROUP_LIMIT_PER_TOPIC_PER_PEER
) {
    private val groups: HashMap<Topic, HashMap<GroupId, GroupState<PeerState>>> = hashMapOf()

    fun getGroup(topic: Topic, groupId: GroupId): GroupState<PeerState>? =
        groups[topic]?.get(groupId)

    /**
     * Returns the group for (topic, groupId), creating it as a locally-initiated group
     * if absent. Resets the TTL if the group already exists.
     */
    fun getOrCreateLocalGroup(topic: Topic, groupId: GroupId): GroupState<PeerState> {
        val topicGroups = groups.getOrPut(topic) { hashMapOf() }
        val existing = topicGroups[groupId]
        if (existing != null) {
            existing.ttlInHeartbeats = groupTtlHeartbeats
            existing.locallyPublished = true // mark as locally published even if peer-initiated
            return existing
        }
        return GroupState<PeerState>(
            ttlInHeartbeats = groupTtlHeartbeats,
            peerInitiated = false,
            firstSeenFromPeer = null,
            locallyPublished = true // new local group is locally published by definition
        ).also { topicGroups[groupId] = it }
    }

    /**
     * Returns the group for (topic, groupId), creating it as a peer-initiated group if absent.
     * Returns null and drops the RPC if either DoS cap would be exceeded.
     */
    fun getOrCreatePeerGroup(topic: Topic, groupId: GroupId, peer: PeerId): GroupState<PeerState>? {
        val topicGroups = groups.getOrPut(topic) { hashMapOf() }
        val existing = topicGroups[groupId]
        if (existing != null) return existing

        val totalPeerInitiated = topicGroups.values.count { it.peerInitiated }
        if (totalPeerInitiated >= peerInitiatedGroupLimitPerTopic) {
            logger.debug(
                "Dropping peer-initiated group {} from {}: per-topic cap {} reached for topic {}",
                groupId,
                peer,
                peerInitiatedGroupLimitPerTopic,
                topic
            )
            return null
        }

        val peerTotal = topicGroups.values.count { it.firstSeenFromPeer == peer }
        if (peerTotal >= peerInitiatedGroupLimitPerTopicPerPeer) {
            logger.debug(
                "Dropping peer-initiated group {} from {}: per-peer cap {} reached for topic {}",
                groupId,
                peer,
                peerInitiatedGroupLimitPerTopicPerPeer,
                topic
            )
            return null
        }

        return GroupState<PeerState>(
            ttlInHeartbeats = groupTtlHeartbeats,
            peerInitiated = true,
            firstSeenFromPeer = peer
        ).also { topicGroups[groupId] = it }
    }

    /** Resets the TTL for (topic, groupId). Called by publishPartial. */
    fun resetTtl(topic: Topic, groupId: GroupId) {
        groups[topic]?.get(groupId)?.let { it.ttlInHeartbeats = groupTtlHeartbeats }
    }

    /** Returns a read-only snapshot of all groups for [topic]. */
    fun groupsForTopic(topic: Topic): Map<GroupId, GroupState<PeerState>> =
        groups[topic] ?: emptyMap()

    /**
     * Decrements TTLs and garbage-collects expired groups (TTL ≤ 0) and
     * groups whose peerStates map has become empty.
     */
    fun onHeartbeat() {
        val topicIter = groups.entries.iterator()
        while (topicIter.hasNext()) {
            val (_, topicGroups) = topicIter.next()
            val groupIter = topicGroups.entries.iterator()
            while (groupIter.hasNext()) {
                val (_, group) = groupIter.next()
                group.ttlInHeartbeats--
                if (group.ttlInHeartbeats <= 0 || group.peerStates.isEmpty()) {
                    groupIter.remove()
                }
            }
            if (topicGroups.isEmpty()) topicIter.remove()
        }
    }

    /**
     * Removes [peer] from all group peerStates; garbage-collects groups that
     * become empty as a result.
     */
    fun onPeerDisconnected(peer: PeerId) {
        val topicIter = groups.entries.iterator()
        while (topicIter.hasNext()) {
            val (_, topicGroups) = topicIter.next()
            val groupIter = topicGroups.entries.iterator()
            while (groupIter.hasNext()) {
                val (_, group) = groupIter.next()
                group.peerStates.remove(peer)
                if (group.peerStates.isEmpty()) groupIter.remove()
            }
            if (topicGroups.isEmpty()) topicIter.remove()
        }
    }

    /** Drops all group state for [topic] (called when we unsubscribe). */
    fun onTopicUnsubscribed(topic: Topic) {
        groups.remove(topic)
    }
}
