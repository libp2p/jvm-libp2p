package io.libp2p.simulate.stats.collect.gossip


/**
 * Number of messages requested via IWant
 */
val GossipMessageResult.iWantRequestCount: Int get() = iWantMessages.sumOf { it.simMsgIds.size }

/**
 * Publish messages sent after corresponding IWant request
 */
val GossipMessageResult.publishesByIWant: List<GossipMessageResult.PubMessageWrapper> get() =
    publishMessages.filter {
        isByIWantPubMessage(it)
    }

/**
 * Lists publish messages that were sent from one peer to another more than once
 */
val GossipMessageResult.duplicatePublishes get() =
    publishMessages
        .groupBy {
            Triple(it.simMsgId, it.origMsg.sendingPeer, it.origMsg.receivingPeer)
        }
        .filterValues { it.size > 1 }
        .values

/**
 * Lists publish messages that were sent between two peers forth and back
 */
val GossipMessageResult.roundPublishes get() =
    publishMessages
        .groupBy {
            Pair(it.simMsgId, setOf(it.origMsg.sendingPeer, it.origMsg.receivingPeer))
        }
        .filterValues {
            it.size > 1 && it.map { it.origMsg.sendingPeer }.distinct().size > 1
        }
        .values

/**
 * Deliveries (first arrived published message) received due to IWant request
 */
fun GossipPubDeliveryResult.getDeliveriesByIWant(msgResult: GossipMessageResult):
        List<GossipPubDeliveryResult.MessageDelivery> =
    deliveries.filter {
        msgResult.isByIWantPubMessage(it.origGossipMsg)
    }
