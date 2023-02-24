package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.simulate.gossip.GossipSimPeer

class GossipPubDeliveryResult(
    val deliveries: List<MessageDelivery>
) {

    data class MessagePublish(
        val simMsgId: SimMessageId,
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

    fun aggregateSlowestBySimMessageId(messageGroups: Collection<Set<SimMessageId>>) =
        messageGroups
            .map { msgGroup ->
                this
                    .filter { it.origMsg.simMsgId in msgGroup }
                    .selectSlowestPeerDeliveries()
            }
            .merge()

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

        fun fromGossipMessageResult(gossipMessageResult: GossipMessageResult): GossipPubDeliveryResult {
            val fastestReceives =
                gossipMessageResult.receivedPublishMessagesByPeerFastest.values.flatten()
            val orinatingMessages =
                gossipMessageResult.originatingPublishMessages.mapValues { (_, msg) ->
                    MessagePublish(
                        msg.simMsgId,
                        msg.origMsg.sendingPeer as GossipSimPeer,
                        msg.origMsg.sendTime
                    )
                }

            return fastestReceives
                .map { receivedMsg ->
                    val simMsgId = receivedMsg.simMsgId
                    val origMessage = orinatingMessages[simMsgId]
                        ?: throw IllegalStateException("No originating message with id $simMsgId found")
                    MessageDelivery(
                        origMessage,
                        receivedMsg.origMsg.receivingPeer as GossipSimPeer,
                        receivedMsg.origMsg.receiveTime
                    )
                }
                .filter { it.toPeer != it.origMsg.fromPeer }
                .let { GossipPubDeliveryResult(it) }
        }
    }
}

fun GossipMessageResult.getGossipPubDeliveryResult() = GossipPubDeliveryResult.fromGossipMessageResult(this)
fun Collection<GossipPubDeliveryResult>.merge() = GossipPubDeliveryResult.merge(this)
