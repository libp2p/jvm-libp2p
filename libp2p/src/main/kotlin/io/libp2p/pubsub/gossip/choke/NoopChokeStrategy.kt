package io.libp2p.pubsub.gossip.choke

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipRouterEventListenerAdapter

class NoopChokeStrategy : GossipRouterEventListenerAdapter(), ChokeStrategy {

    override fun getPeersToChoke(): Map<Topic, List<PeerId>> = emptyMap()

    override fun getPeersToUnChoke(): Map<Topic, List<PeerId>> = emptyMap()

    override fun getMeshCandidates(): Map<Topic, List<PeerId>> = emptyMap()
}
