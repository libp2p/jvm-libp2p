package io.libp2p.pubsub.gossip.choke

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipRouterEventListener

interface ChokeStrategy : GossipRouterEventListener {

    fun getPeersToChoke(): Map<Topic, List<PeerId>>
    fun getPeersToUnChoke(): Map<Topic, List<PeerId>>
    fun getMeshCandidates(): Map<Topic, List<PeerId>>
}
