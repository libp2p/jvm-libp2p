package io.libp2p.pubsub.flood

import io.libp2p.etc.types.anyComplete
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.TopicSubscriptionFilter
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

class FloodRouter : AbstractRouter(subscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter()) {

    override val protocol = PubsubProtocol.Floodsub

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
        val sentFutures = getTopicsPeers(msg.topics)
            .filter { it != receivedFrom }
            .map { submitPublishMessage(it, msg) }
        return anyComplete(sentFutures)
    }
}
