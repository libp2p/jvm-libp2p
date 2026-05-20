package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import pubsub.pb.Rpc

enum class GossipExtension {
    // Canonical extensions
    PARTIAL_MESSAGES,

    // Non-canonical extensions
    TEST_EXTENSION
}

data class GossipExtensionsConfig(
    val partialMessagesEnabled: Boolean = false,
    val testExtensionEnabled: Boolean = false
)

class GossipExtensionsState(gossipExtensionsConfig: GossipExtensionsConfig? = null) {

    val localExtensionSupport: Rpc.ControlExtensions = Rpc.ControlExtensions.newBuilder()
        .setTestExtension(gossipExtensionsConfig?.testExtensionEnabled ?: false)
        .setPartialMessages(gossipExtensionsConfig?.partialMessagesEnabled ?: false)
        .build()

    /*
        Tracks the peers that we have already sent a control extensions message
     */
    private val outgoingControlExtensionsMsgPeers: MutableSet<PeerId> = mutableSetOf()

    /*
        Tracks peers that already sent us a control extensions message
     */
    private val peerExtensionSupportMap: MutableMap<PeerId, Rpc.ControlExtensions> = mutableMapOf()

    fun onPeerDisconnected(peer: PeerId) {
        outgoingControlExtensionsMsgPeers.remove(peer)
        peerExtensionSupportMap.remove(peer)
    }

    fun onControlExtensionsMessage(ctrlExtensions: Rpc.ControlExtensions, receivedFrom: PeerId) {
        peerExtensionSupportMap[receivedFrom] = ctrlExtensions
    }

    fun registerControlExtensionMessageSentToPeers(peerId: PeerId) {
        outgoingControlExtensionsMsgPeers.add(peerId)
    }

    fun peerSupportedExtensions(peerId: PeerId) = peerExtensionSupportMap[peerId]

    fun hasReceivedControlExtensionsFrom(peer: PeerId) =
        peerExtensionSupportMap.contains(peer)

    fun hasSentControlExtensionsTo(peer: PeerId) =
        outgoingControlExtensionsMsgPeers.contains(peer)

    fun testExtensionsEnabled() = localExtensionSupport.testExtension
    fun peerSupportsTestExtensions(peerId: PeerId) =
        peerExtensionSupportMap[peerId]?.testExtension == true

    fun partialMessagesEnabled() = localExtensionSupport.partialMessages
    fun peerSupportsPartialMessages(peerId: PeerId) =
        peerExtensionSupportMap[peerId]?.partialMessages == true
}
