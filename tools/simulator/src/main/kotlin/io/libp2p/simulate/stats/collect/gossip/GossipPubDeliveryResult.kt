package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.simulate.gossip.GossipSimPeer

class GossipPubDeliveryResult(
    val deliveries: List<MessageDelivery>
) {

    data class MessagePublish(
        val msgId: Long,
        val fromPeer: GossipSimPeer,
        val sentTime: Long,
    )

    data class MessageDelivery(
        val origMsg: MessagePublish,
        val toPeer: GossipSimPeer,
        val receivedTime: Long
    ) {
        val deliveryDelay get() = receivedTime - origMsg.sentTime
    }

    val originalMessages: List<MessagePublish> by lazy {
        deliveries
            .map { it.origMsg }
            .distinct()
    }

    val publishTimes by lazy {
        originalMessages
            .map { it.sentTime }
            .distinct()
    }

    val deliveryDelays by lazy {
        deliveries
            .map { it.receivedTime - it.origMsg.sentTime }
    }

    fun filter(predicate: (MessageDelivery) -> Boolean): GossipPubDeliveryResult =
        GossipPubDeliveryResult(deliveries.filter { predicate(it) })

    fun <K> groupBy(keySelectror: (MessageDelivery) -> K): Map<K, GossipPubDeliveryResult> =
        deliveries
            .groupBy { keySelectror(it) }
            .mapValues { GossipPubDeliveryResult(it.value) }

    fun selectSlowestPeerDeliveries(): GossipPubDeliveryResult =
        deliveries
            .groupingBy { it.toPeer.peerId }
            .reduce { _, accumulator, element ->
                listOf(accumulator, element).maxByOrNull { it.receivedTime }!!
            }
            .values
            .sortedBy { it.receivedTime }
            .let { GossipPubDeliveryResult(it) }

    fun sliceByPublishTime(publishTime: Long) = filter { it.origMsg.sentTime == publishTime }

    fun aggregateSlowestByPublishTime() =
        publishTimes
            .map { sliceByPublishTime(it).selectSlowestPeerDeliveries() }
            .merge()

    companion object {
        fun merge(stats: Collection<GossipPubDeliveryResult>): GossipPubDeliveryResult =
            stats
                .flatMap { it.deliveries }
                .sortedBy { it.receivedTime }
                .let { GossipPubDeliveryResult(it) }
    }
}

fun Collection<GossipPubDeliveryResult>.merge() = GossipPubDeliveryResult.merge(this)
