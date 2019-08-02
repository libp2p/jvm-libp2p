package io.libp2p.pubsub.flood

import io.libp2p.core.types.anyComplete
import io.libp2p.pubsub.AbstractRouter
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

class FloodRouter : AbstractRouter() {

    // msg: validated unseen messages received from api
    override fun broadcastOutbound(msg: Rpc.Message): CompletableFuture<Unit> {
        val ret = broadcast(msg, null)
        flushAllPending()
        return ret
    }

    // msg: validated unseen messages received from wire
    override fun broadcastInbound(msg: Rpc.RPC, receivedFrom: StreamHandler) {
        msg.publishList.forEach { broadcast(it, receivedFrom) }
        flushAllPending()
    }

    private fun broadcast(msg: Rpc.Message, receivedFrom: StreamHandler?): CompletableFuture<Unit> {
        val sentFutures = getTopicsPeers(msg.topicIDsList)
            .filter { it != receivedFrom }
            .map { submitPublishMessage(it, msg) }
        return anyComplete(sentFutures)
    }
}