package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.simulate.gossip.SimMessage
import io.libp2p.simulate.gossip.SimMessageDelivery

class GossipPubDeliveryStats(
    val deliveries: List<PubMessageDelivery>
) {

    data class PubMessageDelivery(
        val origMsg: SimMessage,
        val deliveredMsg: SimMessageDelivery
    )

    val originalMessages: List<SimMessage> by lazy {
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
            .map { it.deliveredMsg.receivedTime - it.origMsg.sentTime }
    }

    fun filter(predicate: (PubMessageDelivery) -> Boolean): GossipPubDeliveryStats =
        GossipPubDeliveryStats(deliveries.filter { predicate(it) })

    fun selectSlowestPeerDeliveries(): GossipPubDeliveryStats =
        deliveries
            .groupingBy { it.deliveredMsg.receivedPeer }
            .reduce { _, accumulator, element ->
                listOf(accumulator, element).maxByOrNull { it.deliveredMsg.receivedTime }!!
            }
            .values
            .sortedBy { it.deliveredMsg.receivedTime }
            .let { GossipPubDeliveryStats(it) }

    fun sliceByPublishTime(publishTime: Long) = filter { it.origMsg.sentTime == publishTime }

    fun aggregateSlowestByPublishTime() =
        publishTimes
            .map { sliceByPublishTime(it).selectSlowestPeerDeliveries() }
            .merge()

    companion object {
        fun merge(stats: Collection<GossipPubDeliveryStats>): GossipPubDeliveryStats =
            stats
                .flatMap { it.deliveries }
                .sortedBy { it.deliveredMsg.receivedTime }
                .let { GossipPubDeliveryStats(it) }
    }
}

fun Collection<GossipPubDeliveryStats>.merge() = GossipPubDeliveryStats.merge(this)
