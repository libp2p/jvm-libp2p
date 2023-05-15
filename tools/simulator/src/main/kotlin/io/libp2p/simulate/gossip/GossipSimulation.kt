package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.*
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.stats.collect.gossip.GossipMessageCollector
import io.libp2p.simulate.stats.collect.gossip.GossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.gossip.SimMessageId
import io.libp2p.simulate.stats.collect.gossip.getMessageIdGenerator
import io.libp2p.tools.schedule
import io.netty.buffer.Unpooled
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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

    private val subscriptions = mutableMapOf<GossipSimPeer, MutableMap<Topic, PubsubSubscription>>()

    private val publishedMessages = mutableListOf<SimMessage>()
    private val pendingValidationCount = AtomicInteger()
    private val deliveredMessagesCount = mutableMapOf<SimMessageId, AtomicInteger>()

    val currentTimeSupplier: CurrentTimeSupplier = { network.timeController.time }

    private val anyGossipPeer get() = network.peers.values.first()
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
                subscribe(peer, topic)
            }
        }
    }

    private fun onNewApiMessage(msg: MessageApi) {
        val simMessageId = cfg.messageGenerator.messageIdRetriever(msg.data.array())
        deliveredMessagesCount.computeIfAbsent(simMessageId) { AtomicInteger() }.incrementAndGet()
    }

    fun subscribe(peer: GossipSimPeer, topic: Topic) {
        check(!(subscriptions[peer]?.contains(topic) ?: false))
        val subscription = peer.api.subscribe(
            Validator { message ->
                onNewApiMessage(message)
                val (validationDelay, validationResult) = cfg.messageValidationGenerator(peer, message)
                if (validationDelay == Duration.ZERO) {
                    CompletableFuture.completedFuture(validationResult)
                } else {
                    val ret = CompletableFuture<ValidationResult>()
                    pendingValidationCount.incrementAndGet()
                    peer.simExecutor.schedule(validationDelay) {
                        ret.complete(validationResult)
                        pendingValidationCount.decrementAndGet()
                    }
                    ret
                }
            },
            topic
        )
        subscriptions.computeIfAbsent(peer) { mutableMapOf() }[topic] = subscription
    }

    fun unsubscribe(peer: GossipSimPeer, topic: Topic) {
        val peerSubscriptions = subscriptions[peer]
            ?: throw IllegalArgumentException("No subscriptions found for peer $peer")
        val subscription = peerSubscriptions.remove(topic)
            ?: throw IllegalArgumentException("Peer $peer is not subscribed to topic '$topic'")
        subscription.unsubscribe()
    }

    fun forwardTime(duration: Duration): Long {
        network.timeController.addTime(duration.toJavaDuration())
        return network.timeController.time
    }

    fun forwardTimeUntilAllPubDelivered(step: Duration = 1.seconds, maxDuration: Duration = 1.minutes) {
        var totalDuration = 0.seconds
        while (totalDuration <= maxDuration && !isAllMessagesDelivered()) {
            network.timeController.addTime(step.toJavaDuration())
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
            network.timeController.addTime(step.toJavaDuration())
            totalDuration += step
        }
    }

    fun isAllMessagesDelivered(): Boolean =
        deliveredMessagesCount.values.sumOf { it.get() } == publishedMessages.size * (network.peers.size - 1)

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
        gossipMessageCollector.clear()
    }
}
