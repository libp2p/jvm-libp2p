package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.stats.collect.gossip.GossipMessageCollector
import io.libp2p.simulate.stats.collect.gossip.GossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.gossip.getMessageIdGenerator
import io.libp2p.simulate.util.countValuesBy
import io.netty.buffer.Unpooled
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

data class SimMessage(
    val msgId: Long,
    val sendingPeer: Int,
    val sentTime: Long,
    val pubResult: CompletableFuture<Unit>
)

data class SimMessageDelivery(
    val msgId: Long,
    val receivedPeer: Int,
    val receivedTime: Long
)

class GossipSimulation(
    val cfg: GossipSimConfig,
    val network: GossipSimNetwork
) {

    private val idCounter = AtomicLong(1)
    val publishedMessages = mutableListOf<SimMessage>()

    val currentTimeSupplier: CurrentTimeSupplier = { network.timeController.time }

    val anyGossipPeer get() = network.peers.values.first()
    val gossipMessageCollector = GossipMessageCollector(
        network.network,
        currentTimeSupplier,
        cfg.messageGenerator,
        anyGossipPeer.getMessageIdGenerator()
    )

    init {
        subscribeAll()
        forwardTime(cfg.warmUpDelay)
    }

    private fun subscribeAll() {
        network.peers.values.forEach { peer ->
            cfg.topics.forEach { topic ->
                peer.subscribe(topic)
            }
        }
    }

    fun forwardTime(duration: Duration): Long {
        network.timeController.addTime(duration)
        return network.timeController.time
    }

    fun forwardTimeUntilAllPubDelivered(step: Duration = 1.seconds, maxDuration: Duration = 1.minutes) {
        var totalDuration = 0.seconds
        while (totalDuration <= maxDuration && !isAllMessagesDelivered()) {
            network.timeController.addTime(step)
            totalDuration += step
        }
    }

    fun forwardTimeUntilNoPendingMessages(
        step: Duration = 1.seconds,
        maxDuration: Duration = 1.minutes,
        maxPendingMessagesAllowed: Int = 10
    ) {
        var totalDuration = 0.seconds
        while (totalDuration <= maxDuration && gossipMessageCollector.pendingMessages.size > maxPendingMessagesAllowed) {
            network.timeController.addTime(step)
            totalDuration += step
        }
    }

    fun isAllMessagesDelivered(): Boolean {
        val deliveredMessageCounts = network.peers
            .mapValues { it.value.inboundMessages.size }
        val sentMessageCounts = publishedMessages.countValuesBy { it.sendingPeer }
        val allMessages = deliveredMessageCounts
            .mapValues { it.value + (sentMessageCounts[it.key] ?: 0) }
        return allMessages.values.all { it == publishedMessages.size }
    }

    fun publishMessage(srcPeer: Int): SimMessage {
        require(cfg.topics.size == 1)
        return publishMessage(srcPeer, 0, cfg.topics[0])
    }

    fun publishMessage(srcPeer: Int, size: Int, topic: Topic): SimMessage {
        val peer = network.peers[srcPeer] ?: throw IllegalArgumentException("Invalid peer index $srcPeer")
        val msgId = idCounter.incrementAndGet()

        val msg = Unpooled.wrappedBuffer(cfg.messageGenerator.msgGenerator(msgId, size))
        val future = peer.apiPublisher.publish(msg, topic)
        val ret = SimMessage(msgId, srcPeer, network.timeController.time, future)
        publishedMessages += ret
        return ret
    }

    fun gatherPubDeliveryStats(): GossipPubDeliveryResult = gossipMessageCollector.gatherResult().getGossipPubDeliveryResult()

    fun clearAllMessages() {
        network.peers.values.forEach { it.inboundMessages.clear() }
        gossipMessageCollector.clear()
    }
}
