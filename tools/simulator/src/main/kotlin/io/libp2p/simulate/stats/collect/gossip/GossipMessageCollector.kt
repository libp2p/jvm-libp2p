package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Network
import io.libp2p.simulate.gossip.GossipPubMessageGenerator
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import pubsub.pb.Rpc.RPC

class GossipMessageCollector(
    network: Network,
    timeSupplier: CurrentTimeSupplier,
    val msgGenerator: GossipPubMessageGenerator
) : ConnectionsMessageCollector<RPC>(network, timeSupplier) {

    fun gatherResult() =
        GossipMessageResult(connectionMessages.values.flatten(), msgGenerator)
}
