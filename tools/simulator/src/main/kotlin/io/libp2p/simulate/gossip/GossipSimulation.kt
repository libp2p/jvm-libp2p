package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toByteArray
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.stats.collect.gossip.GossipMessageCollector
import io.libp2p.simulate.stats.collect.gossip.GossipPubDeliveryStats
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

    val gossipMessageCollector = GossipMessageCollector(network.network, currentTimeSupplier, cfg.messageGenerator)

    init {
        forwardTime(cfg.warmUpDelay)
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

    fun gatherPubDeliveryStats() = GossipPubDeliveryStats(
        gatherMessageResults().entries.flatMap { (origMsg, deliveries) ->
            deliveries.map {
                GossipPubDeliveryStats.PubMessageDelivery(origMsg, it)
            }
        }
    )

    fun gatherMessageResults(): Map<SimMessage, List<SimMessageDelivery>> {
        val deliveries = network.peers.flatMap { (peerId, peer) ->
            peer.inboundMessages.map {
                val msgId = cfg.messageGenerator.messageIdRetriever(it.first.data.toByteArray())
                SimMessageDelivery(msgId, peerId, it.second)
            }
        }.groupBy { it.msgId }

        val list =
            publishedMessages.associateWith { deliveries[it.msgId] ?: emptyList() }

        return list
    }

    fun clearAllMessages() {
        network.peers.values.forEach { it.inboundMessages.clear() }
    }
}
