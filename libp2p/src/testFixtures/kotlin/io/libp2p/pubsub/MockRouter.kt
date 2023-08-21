package io.libp2p.pubsub

import pubsub.pb.Rpc
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

open class MockRouter(executor: ScheduledExecutorService) : AbstractRouter(
    protocol = PubsubProtocol.Floodsub,
    executor = executor,
    subscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),
    maxMsgSize = Int.MAX_VALUE,
    messageFactory = { DefaultPubsubMessage(it) },
    seenMessages = LRUSeenCache(SimpleSeenCache(), 1000),
    messageValidator = NOP_ROUTER_VALIDATOR
) {

    val inboundMessages: BlockingQueue<Rpc.RPC> = LinkedBlockingQueue()

    fun sendToSingle(msg: Rpc.RPC) {
        if (peers.size != 1) throw IllegalStateException("Too many or no peers: $peers")
        send(peers[0], msg)
    }

    fun waitForMessage(predicate: (Rpc.RPC) -> Boolean): Rpc.RPC {
        var cnt = 0
        while (true) {
            val msg = inboundMessages.poll(5, TimeUnit.SECONDS)
                ?: throw TimeoutException("No matching message received among $cnt")
            if (predicate(msg)) return msg
            cnt++
        }
    }

    public override fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
        return super.send(peer, msg)
    }

    override fun onInbound(peer: PeerHandler, msg: Any) {
        super.onInbound(peer, msg)
        inboundMessages += msg as Rpc.RPC
    }

    override fun broadcastOutbound(msg: PubsubMessage): CompletableFuture<Unit> = CompletableFuture.completedFuture(null)
    override fun broadcastInbound(msgs: List<PubsubMessage>, receivedFrom: PeerHandler) {}
    override fun processControl(ctrl: Rpc.ControlMessage, receivedFrom: PeerHandler) {}
}
