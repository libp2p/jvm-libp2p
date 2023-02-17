package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

interface GossipRouterEventListener {

    fun notifyDisconnected(peerId: PeerId)

    fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr)

    fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage)

    fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>)

    fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage)

    fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage)

    fun notifyMeshed(peerId: PeerId, topic: Topic)

    fun notifyPruned(peerId: PeerId, topic: Topic)

    fun notifyRouterMisbehavior(peerId: PeerId, count: Int)
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

    override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) {
        listeners.forEach { it.notifyUnseenInvalidMessage(peerId, msg) }
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
}
