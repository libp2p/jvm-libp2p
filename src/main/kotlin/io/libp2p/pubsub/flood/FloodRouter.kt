package io.libp2p.pubsub.flood

import io.libp2p.etc.types.anyComplete
import io.libp2p.pubsub.*
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

const val DEFAULT_MAX_SEEN_MESSAGES_LIMIT: Int = 10000

class FloodRouter(executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()) : AbstractRouter(
    protocol = PubsubProtocol.Floodsub,
    executor = executor,
    subscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),
    maxMsgSize = Int.MAX_VALUE,
    messageFactory = { DefaultPubsubMessage(it) },
    seenMessages = LRUSeenCache(SimpleSeenCache(), DEFAULT_MAX_SEEN_MESSAGES_LIMIT),
    messageValidator = NOP_ROUTER_VALIDATOR
) {

    // msg: validated unseen messages received from api
    override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> {
        val ret = broadcast(msg, null)
        flushAllPending()
        return ret
    }

    // msg: validated unseen messages received from wire
    override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {
        msgs.forEach { broadcast(it, receivedFrom) }
        flushAllPending()
    }

    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {
        // NOP
    }

    private fun broadcast(msg: PubsubMessage, receivedFrom: PeerHandler?): CompletableFuture<Unit> {
        val peers = msg.topics
            .map { getTopicPeers(it) }
            .reduce { p1, p2 -> p1 + p2 }
        val sentFutures = peers
            .filter { it != receivedFrom }
            .map { submitPublishMessage(it, msg) }
        return anyComplete(sentFutures)
    }
}
