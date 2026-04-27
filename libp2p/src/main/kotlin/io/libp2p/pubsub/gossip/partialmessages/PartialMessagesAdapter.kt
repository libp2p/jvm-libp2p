package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import org.slf4j.LoggerFactory
import pubsub.pb.Rpc

private val logger = LoggerFactory.getLogger(PartialMessagesAdapterImpl::class.java)

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

    /**
     * Executes the client's [PublishActionsFn], updates group state, and enqueues
     * outbound [Rpc.PartialMessagesExtension] RPCs via [enqueueFn].
     *
     * [peerRequestsPartial] is used to enforce the spec MUST: omit [PublishAction.partialMessage]
     * when the peer supports but did not request partial messages.
     */
    fun publishPartial(
        topic: Topic,
        groupId: GroupId,
        actionsFn: PublishActionsFn<*>,
        peerRequestsPartial: (PeerId) -> Boolean,
        enqueueFn: (PeerId, ByteArray?, ByteArray?) -> Unit
    )
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

    @Suppress("UNCHECKED_CAST")
    override fun publishPartial(
        topic: Topic,
        groupId: GroupId,
        actionsFn: PublishActionsFn<*>,
        peerRequestsPartial: (PeerId) -> Boolean,
        enqueueFn: (PeerId, ByteArray?, ByteArray?) -> Unit
    ) {
        val typedFn = actionsFn as PublishActionsFn<PeerState>
        val groupState = stateStore.getOrCreateLocalGroup(topic, groupId)
        for ((peerId, action) in typedFn.decide(groupState.peerStates, peerRequestsPartial)) {
            if (action.error != null) {
                logger.debug("Skipping partial publish to {}: {}", peerId, action.error.message)
                continue
            }
            // Spec MUST: omit partialMessage if peer supports but didn't request
            val effectivePartialMessage = if (peerRequestsPartial(peerId)) action.partialMessage else null
            if (effectivePartialMessage != null || action.partsMetadata != null) {
                enqueueFn(peerId, effectivePartialMessage, action.partsMetadata)
            }
            if (action.nextPeerState != null) {
                groupState.peerStates[peerId] = action.nextPeerState
            }
        }
    }
}
