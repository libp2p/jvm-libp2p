package io.libp2p.pubsub.gossip.choke

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipRouterEventBroadcaster
import io.libp2p.pubsub.gossip.GossipRouterEventListenerAdapter

typealias TopicStrategyConstructor = (Topic) -> TopicChokeStrategy

class ChokeStrategyPerTopic(
    val topicStrategyCtor: TopicStrategyConstructor
) : ChokeStrategy {

    private val topicStrategies = mutableMapOf<Topic, TopicChokeStrategy>()

    private fun onTopicMeshed(topic: Topic) =
        topicStrategies.computeIfAbsent(topic) {
            topicStrategyCtor(it)
                .also {
                    eventListener.listeners += it.eventListener
                }
        }


    override val eventListener: GossipRouterEventBroadcaster = GossipRouterEventBroadcaster()

    init {
        eventListener.listeners += object : GossipRouterEventListenerAdapter() {
            override fun notifyMeshed(peerId: PeerId, topic: Topic) {
                onTopicMeshed(topic)
            }
        }
    }

    override fun getPeersToChoke(): Map<Topic, List<PeerId>> = topicStrategies
        .mapValues { it.value.getPeersToChoke() }

    override fun getPeersToUnChoke(): Map<Topic, List<PeerId>> = topicStrategies
        .mapValues { it.value.getPeersToUnChoke() }

    override fun getMeshCandidates(): Map<Topic, List<PeerId>> = topicStrategies
        .mapValues { it.value.getMeshCandidates() }
}