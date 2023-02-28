package io.libp2p.pubsub.gossip.choke

import io.libp2p.core.PeerId
import io.libp2p.pubsub.gossip.GossipRouterEventListener

interface TopicChokeStrategy {
    val eventListener: GossipRouterEventListener

    fun getPeersToChoke(): List<PeerId>
    fun getPeersToUnChoke(): List<PeerId>
    fun getMeshCandidates(): List<PeerId>
}