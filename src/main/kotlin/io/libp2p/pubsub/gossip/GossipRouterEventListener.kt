package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

interface GossipRouterEventListener {

    fun notifyDisconnected(peer: P2PService.PeerHandler)

    fun notifyConnected(peer: P2PService.PeerHandler)

    fun notifyUnseenMessage(peer: P2PService.PeerHandler, msg: PubsubMessage)

    fun notifySeenMessage(peer: P2PService.PeerHandler, msg: PubsubMessage, validationResult: Optional<ValidationResult>)

    fun notifyUnseenInvalidMessage(peer: P2PService.PeerHandler, msg: PubsubMessage)

    fun notifyUnseenValidMessage(peer: P2PService.PeerHandler, msg: PubsubMessage)

    fun notifyMeshed(peer: P2PService.PeerHandler, topic: Topic)

    fun notifyPruned(peer: P2PService.PeerHandler, topic: Topic)

    fun notifyRouterMisbehavior(peer: P2PService.PeerHandler, count: Int)
}

class GossipRouterEventBroadcaster : GossipRouterEventListener {
    val listeners = CopyOnWriteArrayList<GossipRouterEventListener>()

    override fun notifyDisconnected(peer: P2PService.PeerHandler) {
        listeners.forEach { it.notifyDisconnected(peer) }
    }

    override fun notifyConnected(peer: P2PService.PeerHandler) {
        listeners.forEach { it.notifyConnected(peer) }
    }

    override fun notifyUnseenMessage(peer: P2PService.PeerHandler, msg: PubsubMessage) {
        listeners.forEach { it.notifyUnseenMessage(peer, msg) }
    }

    override fun notifySeenMessage(
        peer: P2PService.PeerHandler,
        msg: PubsubMessage,
        validationResult: Optional<ValidationResult>
    ) {
        listeners.forEach { it.notifySeenMessage(peer, msg, validationResult) }
    }

    override fun notifyUnseenInvalidMessage(peer: P2PService.PeerHandler, msg: PubsubMessage) {
        listeners.forEach { it.notifyUnseenInvalidMessage(peer, msg) }
    }

    override fun notifyUnseenValidMessage(peer: P2PService.PeerHandler, msg: PubsubMessage) {
        listeners.forEach { it.notifyUnseenValidMessage(peer, msg) }
    }

    override fun notifyMeshed(peer: P2PService.PeerHandler, topic: Topic) {
        listeners.forEach { it.notifyMeshed(peer, topic) }
    }

    override fun notifyPruned(peer: P2PService.PeerHandler, topic: Topic) {
        listeners.forEach { it.notifyPruned(peer, topic) }
    }

    override fun notifyRouterMisbehavior(peer: P2PService.PeerHandler, count: Int) {
        listeners.forEach { it.notifyRouterMisbehavior(peer, count) }
    }
}