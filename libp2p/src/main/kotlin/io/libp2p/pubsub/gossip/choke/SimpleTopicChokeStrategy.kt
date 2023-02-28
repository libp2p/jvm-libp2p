package io.libp2p.pubsub.gossip.choke

import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.createLRUMap
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipRouterEventListenerAdapter
import java.util.*

/**
 * Only chokes the peers in reverse order of message delivery
 */
class SimpleTopicChokeStrategy(
    topic: Topic
) : TopicChokeStrategy {

    override val eventListener = object : GossipRouterEventListenerAdapter() {
        override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {
            notifyAnyMessage(peerId, msg)
        }

        override fun notifySeenMessage(
            peerId: PeerId,
            msg: PubsubMessage,
            validationResult: Optional<ValidationResult>
        ) {
            notifyAnyMessage(peerId, msg)
        }

        fun notifyAnyMessage(peerId: PeerId, msg: PubsubMessage) {
            if (topic in msg.topics) {
                onTopicMessage(peerId, msg.messageId)
            }
        }
    }

    private val messages = createLRUMap<MessageId, MutableList<PeerId>>(100)

    private fun onTopicMessage(peerId: PeerId, messageId: MessageId) {
        messages.computeIfAbsent(messageId) { mutableListOf() } += peerId
    }

    override fun getPeersToChoke(): List<PeerId> {
        return messages.values
            .flatMap { it.withIndex() }
            .groupingBy { it.value }
            .fold(0) { acc, v -> acc + v.index }
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    override fun getPeersToUnChoke(): List<PeerId> = emptyList()
    override fun getMeshCandidates(): List<PeerId> = emptyList()
}