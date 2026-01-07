package io.libp2p.pubsub.partial

import io.libp2p.core.PeerId
import pubsub.pb.Rpc

/**
 * Configuration for partial message extension.
 * Applications provide callbacks to customize behavior.
 *
 * @property metadataMerger Merges parts metadata from multiple sources.
 * @property validateRpc Validates incoming partial RPC before processing. Return false to reject.
 * @property onIncomingRpc Called when a partial message RPC is received.
 * @property groupTtlHeartbeats TTL in heartbeats for group state.
 * @property maxPeerInitiatedGroupsPerTopic Maximum peer-initiated groups per topic.
 * @property maxPeerInitiatedGroupsPerTopicPerPeer Maximum peer-initiated groups per topic per peer.
 */
class PartialMessageExtension(
    val metadataMerger: PartsMetadataMerger = BitwiseOrMerger,
    val validateRpc: (peerId: PeerId, rpc: Rpc.PartialMessagesExtension) -> Boolean = { _, _ -> true },
    val onIncomingRpc: (peerId: PeerId, rpc: Rpc.PartialMessagesExtension) -> Unit = { _, _ -> },
    val groupTtlHeartbeats: Int = 3,
    val maxPeerInitiatedGroupsPerTopic: Int = 255,
    val maxPeerInitiatedGroupsPerTopicPerPeer: Int = 8
)
