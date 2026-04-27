package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc

/**
 * Type-erased view of the partial-messages subsystem used by [io.libp2p.pubsub.gossip.GossipRouter].
 *
 * All methods are called on the pubsub event thread.
 */
internal interface PartialMessagesAdapter {
    fun onPeerDisconnected(peer: PeerId)
    fun onTopicUnsubscribed(topic: Topic)
    fun onHeartbeat()
    fun onIncomingRpc(topic: Topic, from: PeerId, rpc: Rpc.PartialMessagesExtension)
}

/**
 * Bridges [GossipRouter] (which has no [PeerState] type parameter) to the typed
 * [PartialMessagesHandler] and [PartialGroupStateStore].
 *
 * Created once in [io.libp2p.pubsub.gossip.builders.GossipRouterBuilder] with an
 * unchecked cast that is safe because [PeerState] is captured and used consistently
 * throughout the lifetime of this object.
 */
internal class PartialMessagesAdapterImpl<PeerState>(
    val handler: PartialMessagesHandler<PeerState>,
    val stateStore: PartialGroupStateStore<PeerState>,
    val feedback: PartialMessagesPeerFeedback
) : PartialMessagesAdapter {

    override fun onPeerDisconnected(peer: PeerId) = stateStore.onPeerDisconnected(peer)
    override fun onTopicUnsubscribed(topic: Topic) = stateStore.onTopicUnsubscribed(topic)
    override fun onHeartbeat() = stateStore.onHeartbeat()

    override fun onIncomingRpc(topic: Topic, from: PeerId, rpc: Rpc.PartialMessagesExtension) {
        val groupId = rpc.groupID.toByteArray().toGroupId()
        val groupState = stateStore.getOrCreatePeerGroup(topic, groupId, from) ?: return
        handler.onIncomingRpc(from, groupState.peerStates, rpc, feedback)
    }
}
