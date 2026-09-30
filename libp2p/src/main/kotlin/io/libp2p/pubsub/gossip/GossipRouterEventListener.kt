package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.MessageRejectReason
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import org.slf4j.LoggerFactory
import pubsub.pb.Rpc
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

interface GossipRouterEventListener {

    fun notifyDisconnected(peerId: PeerId)

    fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr)

    fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage)

    fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>)

    @Deprecated("Override the overload that also receives the MessageRejectReason")
    fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) {}

    /**
     * Called for a first-seen message that was rejected, with the [reason] it was rejected.
     *
     * Defaulted to the deprecated two-argument overload so that existing implementations keep
     * working unchanged.
     */
    fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage, reason: MessageRejectReason) {
        @Suppress("DEPRECATION")
        notifyUnseenInvalidMessage(peerId, msg)
    }

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

    /**
     * Called when the application handler returned [ValidationResult.Ignore] for a first-seen
     * message. Distinct from a rejection: the message is dropped without penalising the sender.
     */
    fun notifyUnseenIgnoredMessage(peerId: PeerId, msg: PubsubMessage) {}

    /** Called for an inbound message on a topic this router is not subscribed to. */
    fun notifyNonSubscribedMessage(peerId: PeerId, msg: Rpc.Message) {}

    /** Called when this router joins [topic]. Not called again for a topic it has already joined. */
    fun notifySubscribed(topic: Topic) {}

    /** Called when this router leaves [topic]. Not called for a topic it had not joined. */
    fun notifyUnsubscribed(topic: Topic) {}
}

private val logger = LoggerFactory.getLogger(GossipRouterEventBroadcaster::class.java)

class GossipRouterEventBroadcaster : GossipRouterEventListener {
    val listeners = CopyOnWriteArrayList<GossipRouterEventListener>()

    /**
     * Listeners run on the router's event thread, some on the outbound write path. A throwing
     * listener must not break message processing or starve the listeners after it.
     */
    private inline fun forEachListener(action: (GossipRouterEventListener) -> Unit) {
        listeners.forEach {
            try {
                action(it)
            } catch (e: Exception) {
                logger.warn("GossipRouterEventListener {} failed", it, e)
            }
        }
    }

    override fun notifyDisconnected(peerId: PeerId) {
        forEachListener { it.notifyDisconnected(peerId) }
    }

    override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {
        forEachListener { it.notifyConnected(peerId, peerAddress) }
    }

    override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {
        forEachListener { it.notifyUnseenMessage(peerId, msg) }
    }

    override fun notifySeenMessage(
        peerId: PeerId,
        msg: PubsubMessage,
        validationResult: Optional<ValidationResult>
    ) {
        forEachListener { it.notifySeenMessage(peerId, msg, validationResult) }
    }

    override fun notifyUnseenInvalidMessage(
        peerId: PeerId,
        msg: PubsubMessage,
        reason: MessageRejectReason
    ) {
        forEachListener { it.notifyUnseenInvalidMessage(peerId, msg, reason) }
    }

    @Deprecated("Call the overload that also receives the MessageRejectReason")
    override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) =
        notifyUnseenInvalidMessage(peerId, msg, MessageRejectReason.ValidationFailed)

    override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {
        forEachListener { it.notifyUnseenValidMessage(peerId, msg) }
    }

    override fun notifyMeshed(peerId: PeerId, topic: Topic) {
        forEachListener { it.notifyMeshed(peerId, topic) }
    }

    override fun notifyPruned(peerId: PeerId, topic: Topic) {
        forEachListener { it.notifyPruned(peerId, topic) }
    }

    override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {
        forEachListener { it.notifyRouterMisbehavior(peerId, count) }
    }

    override fun notifySlowPeer(peerId: PeerId) {
        forEachListener { it.notifySlowPeer(peerId) }
    }

    override fun notifyRpcReceived(peerId: PeerId, rpc: Rpc.RPC) {
        forEachListener { it.notifyRpcReceived(peerId, rpc) }
    }

    override fun notifyRpcSent(peerId: PeerId, rpc: Rpc.RPC) {
        forEachListener { it.notifyRpcSent(peerId, rpc) }
    }

    override fun notifyRpcDropped(peerId: PeerId, rpc: Rpc.RPC) {
        forEachListener { it.notifyRpcDropped(peerId, rpc) }
    }

    override fun notifyUnseenIgnoredMessage(peerId: PeerId, msg: PubsubMessage) {
        forEachListener { it.notifyUnseenIgnoredMessage(peerId, msg) }
    }

    override fun notifyNonSubscribedMessage(peerId: PeerId, msg: Rpc.Message) {
        forEachListener { it.notifyNonSubscribedMessage(peerId, msg) }
    }

    override fun notifySubscribed(topic: Topic) {
        forEachListener { it.notifySubscribed(topic) }
    }

    override fun notifyUnsubscribed(topic: Topic) {
        forEachListener { it.notifyUnsubscribed(topic) }
    }
}
