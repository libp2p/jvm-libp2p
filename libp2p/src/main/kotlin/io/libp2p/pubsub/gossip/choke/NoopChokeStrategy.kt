package io.libp2p.pubsub.gossip.choke

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipRouterEventListener
import io.libp2p.pubsub.gossip.GossipRouterEventListenerAdapter

class NoopChokeStrategy : ChokeStrategy {

    override val eventListener = GossipRouterEventListenerAdapter()

    override fun getPeersToChoke(): Map<Topic, List<PeerId>> = emptyMap()

    override fun getPeersToUnChoke(): Map<Topic, List<PeerId>> = emptyMap()

    override fun getMeshCandidates(): Map<Topic, List<PeerId>> = emptyMap()
}
