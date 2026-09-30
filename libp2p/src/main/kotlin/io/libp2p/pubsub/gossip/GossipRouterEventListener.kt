package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.MessageRejectReason
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import pubsub.pb.Rpc
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

interface GossipRouterEventListener {

    fun notifyDisconnected(peerId: PeerId)

    fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr)

    fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage)

    fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>)

    fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage, reason: MessageRejectReason)

    fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage)

    fun notifyMeshed(peerId: PeerId, topic: Topic)

    fun notifyPruned(peerId: PeerId, topic: Topic)

    fun notifyRouterMisbehavior(peerId: PeerId, count: Int)

    /**
     * Called when a peer's outbound RPC parts queue remains above the configured slow-peer
     * threshold for the configured number of heartbeats.
     */
    fun notifySlowPeer(peerId: PeerId)

    /**
     * Called for every inbound RPC before any of it is processed.
     *
     * The control messages an RPC carries (IHAVE/IWANT/GRAFT/PRUNE/IDONTWANT) are not otherwise
     * observable from outside the router; inspect [Rpc.RPC.getControl] to count them.
     *
     * Defaulted to a no-op so that existing implementations keep compiling.
     */
    fun notifyRpcReceived(peerId: PeerId, rpc: Rpc.RPC) {}

    /** Called for every outbound RPC, immediately before it is written. */
    fun notifyRpcSent(peerId: PeerId, rpc: Rpc.RPC) {}

    /**
     * Called for an inbound RPC discarded without being processed: the peer is not accepted right
     * now, the RPC exceeded the configured list limits, or the subscription filter rejected it.
     */
    fun notifyRpcDropped(peerId: PeerId, rpc: Rpc.RPC) {}
}

class GossipRouterEventBroadcaster : GossipRouterEventListener {
    val listeners = CopyOnWriteArrayList<GossipRouterEventListener>()

    override fun notifyDisconnected(peerId: PeerId) {
        listeners.forEach { it.notifyDisconnected(peerId) }
    }

    override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {
        listeners.forEach { it.notifyConnected(peerId, peerAddress) }
    }

    override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {
        listeners.forEach { it.notifyUnseenMessage(peerId, msg) }
    }

    override fun notifySeenMessage(
        peerId: PeerId,
        msg: PubsubMessage,
        validationResult: Optional<ValidationResult>
    ) {
        listeners.forEach { it.notifySeenMessage(peerId, msg, validationResult) }
    }

    override fun notifyUnseenInvalidMessage(
        peerId: PeerId,
        msg: PubsubMessage,
        reason: MessageRejectReason
    ) {
        listeners.forEach { it.notifyUnseenInvalidMessage(peerId, msg, reason) }
    }

    override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {
        listeners.forEach { it.notifyUnseenValidMessage(peerId, msg) }
    }

    override fun notifyMeshed(peerId: PeerId, topic: Topic) {
        listeners.forEach { it.notifyMeshed(peerId, topic) }
    }

    override fun notifyPruned(peerId: PeerId, topic: Topic) {
        listeners.forEach { it.notifyPruned(peerId, topic) }
    }

    override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {
        listeners.forEach { it.notifyRouterMisbehavior(peerId, count) }
    }

    override fun notifySlowPeer(peerId: PeerId) {
        listeners.forEach { it.notifySlowPeer(peerId) }
    }

    override fun notifyRpcReceived(peerId: PeerId, rpc: Rpc.RPC) {
        listeners.forEach { it.notifyRpcReceived(peerId, rpc) }
    }

    override fun notifyRpcSent(peerId: PeerId, rpc: Rpc.RPC) {
        listeners.forEach { it.notifyRpcSent(peerId, rpc) }
    }

    override fun notifyRpcDropped(peerId: PeerId, rpc: Rpc.RPC) {
        listeners.forEach { it.notifyRpcDropped(peerId, rpc) }
    }
}
