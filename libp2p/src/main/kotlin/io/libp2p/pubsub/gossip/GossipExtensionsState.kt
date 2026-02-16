package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import pubsub.pb.Rpc

class GossipExtensionsState {

    /*
        Tracks the peers that we have already sent an extension control message
     */
    private val outgoingExtensionControlMsgPeers: MutableList<PeerId> = mutableListOf()

    /*
        Tracks peers that already sent us an extension control message
     */
    private val peerExtensionSupportMap: MutableMap<PeerId, Rpc.ControlExtensions> = mutableMapOf()

    fun onPeerDisconnected(peer: PeerId) {
        outgoingExtensionControlMsgPeers.remove(peer)
        peerExtensionSupportMap.remove(peer)
    }

    fun onExtensionControlMessage(ctrlExtensions: Rpc.ControlExtensions, receivedFrom: PeerId) {
        peerExtensionSupportMap[receivedFrom] = ctrlExtensions
    }

    fun peerSupportedExtensions(peerId: PeerId) = peerExtensionSupportMap[peerId]

    fun hasReceivedExtensionControlFrom(peer: PeerId) =
        peerExtensionSupportMap.contains(peer)

    fun hasSentExtensionControlTo(peer: PeerId) =
        outgoingExtensionControlMsgPeers.contains(peer)
}
