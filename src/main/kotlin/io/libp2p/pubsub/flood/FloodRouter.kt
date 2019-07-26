package io.libp2p.pubsub.flood

import io.libp2p.core.types.anyComplete
import io.libp2p.pubsub.AbstractRouter
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture

class FloodRouter : AbstractRouter() {

    // msg: validated unseen messages received from api
    override fun broadcastOutbound(msg: Rpc.RPC): CompletableFuture<Unit> {
        val sentFutures = peers
            .map { send(it, msg) }
        return anyComplete(sentFutures)
    }

    // msg: validated unseen messages received from wire
    override fun broadcastInbound(msg: Rpc.RPC, receivedFrom: StreamHandler) {
        peers.filter { it != receivedFrom }
            .forEach { send(it, msg) }
    }

    override fun subscribe(vararg topics: ByteArray) {
        // NOP
    }

    override fun unsubscribe(vararg topics: ByteArray) {
        // NOP
    }
}