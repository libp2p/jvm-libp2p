package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Network
import io.libp2p.simulate.gossip.GossipPubMessageGenerator
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import pubsub.pb.Rpc
import pubsub.pb.Rpc.RPC

typealias GossipMessageIdGenerator = (Rpc.Message) -> GossipMessageId

fun GossipSimPeer.getMessageIdGenerator(): GossipMessageIdGenerator = {
    this.router.messageFactory(it).messageId
}

class GossipMessageCollector(
    network: Network,
    timeSupplier: CurrentTimeSupplier,
    val msgGenerator: GossipPubMessageGenerator,
    val gossipMessageIdGenerator: GossipMessageIdGenerator
) : ConnectionsMessageCollector<RPC>(network, timeSupplier) {

    fun gatherResult() =
        GossipMessageResult(deliveredMessages, msgGenerator, gossipMessageIdGenerator)
}
