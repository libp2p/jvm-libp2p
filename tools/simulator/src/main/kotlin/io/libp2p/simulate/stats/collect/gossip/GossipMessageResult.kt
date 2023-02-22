package io.libp2p.simulate.stats.collect.gossip

import com.google.protobuf.AbstractMessage
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.gossip.GossipPubMessageGenerator
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.simulate.stats.collect.CollectedMessage
import pubsub.pb.Rpc

typealias GossipMessageId = MessageId
typealias SimMessageId = Long

class GossipMessageResult(
    val messages: List<CollectedMessage<Rpc.RPC>>,
    private val msgGenerator: GossipPubMessageGenerator,
    private val gossipMessageIdGenerator: GossipMessageIdGenerator
) {

    interface MessageWrapper<TMessage> {
        val origMsg: CollectedMessage<TMessage>
    }

    data class GraftMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlGraft>,
    ) : MessageWrapper<Rpc.ControlGraft> {
        override fun toString() = "Graft[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class PruneMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlPrune>,
    ) : MessageWrapper<Rpc.ControlPrune> {
        override fun toString() = "Prune[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class IHaveMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlIHave>,
        val simMsgId: List<SimMessageId>
    ) : MessageWrapper<Rpc.ControlIHave> {
        override fun toString() = "IHave[$origMsg, messageIds: $simMsgId]"
    }

    data class IWantMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlIWant>,
        val simMsgId: List<SimMessageId>
    ) : MessageWrapper<Rpc.ControlIWant> {
        override fun toString() = "IWant[$origMsg, messageIds: $simMsgId]"
    }

    data class PubMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.Message>,
        val simMsgId: SimMessageId,
        val gossipMsgId: GossipMessageId
    ) : MessageWrapper<Rpc.Message>

    val publishMessages by lazy {
        messages
            .flatMap { collMsg ->
                collMsg.message.publishList.map { pubMsg ->
                    val origMsg = collMsg.withMessage(pubMsg)
                    PubMessageWrapper(
                        origMsg,
                        msgGenerator.messageIdRetriever(origMsg.message.data.toByteArray()),
                        gossipMessageIdGenerator(pubMsg)
                    )
                }
            }
    }

    val originatingPublishMessages: Map<SimMessageId, PubMessageWrapper> by lazy {
        publishMessages
            .groupBy { it.simMsgId }
            .mapValues { (messageId, pubMessages) ->
                val sendingPers = pubMessages.groupBy { it.origMsg.sendingPeer }
                val receivingPers = pubMessages.groupBy { it.origMsg.receivingPeer }
                val onlySendingPeers = sendingPers - receivingPers.keys
                require(onlySendingPeers.size == 1)
                onlySendingPeers.values.first().first()
            }
    }

    private val gossipMessageIdToSimMessageIdMap: Map<GossipMessageId, SimMessageId> by lazy {
        originatingPublishMessages
            .map { it.value.gossipMsgId to it.key }
            .toMap()
    }

    private fun <TMessage : AbstractMessage, TMsgWrapper : MessageWrapper<TMessage>> flattenControl(
        listExtractor: (Rpc.ControlMessage) -> Collection<TMessage>,
        messageFactory: (CollectedMessage<TMessage>) -> TMsgWrapper
    ): List<TMsgWrapper> =
        messages
            .filter { it.message.hasControl() }
            .flatMap { collMsg ->
                listExtractor(collMsg.message.control).map {
                    messageFactory(
                        collMsg.withMessage(it)
                    )
                }
            }

    val graftMessages by lazy {
        flattenControl({ it.graftList }, { GraftMessageWrapper(it) })
    }
    val pruneMessages by lazy {
        flattenControl({ it.pruneList }, { PruneMessageWrapper(it) })
    }
    val iHaveMessages by lazy {
        flattenControl({ it.ihaveList }, {
            IHaveMessageWrapper(
                it,
                it.message.messageIDsList.map {
                    val id = it.toWBytes()
                    gossipMessageIdToSimMessageIdMap[id] ?: throw IllegalStateException("Message with id $id no found")
                }
            )
        })
    }

    val iWantMessages by lazy {
        flattenControl({ it.iwantList }, {
            IWantMessageWrapper(
                it,
                it.message.messageIDsList.map {
                    val id = it.toWBytes()
                    gossipMessageIdToSimMessageIdMap[id] ?: throw IllegalStateException("Message with id $id no found")
                }
            )
        })
    }

    val allGossipMessages by lazy {
        (publishMessages + graftMessages + pruneMessages + iHaveMessages + iWantMessages)
            .sortedBy { it.origMsg.sendTime }
    }

    val receivedPublishMessagesByPeer by lazy {
        publishMessages.groupBy { it.origMsg.receivingPeer }
    }
    val receivedPublishMessagesByPeerFastest by lazy {
        receivedPublishMessagesByPeer.mapValues { (_, msgs) ->
            msgs
                .groupBy { it.simMsgId }
                .values
                .map { idMsgs ->
                    idMsgs.minByOrNull { it.origMsg.receiveTime }
                }
                .filterNotNull()
        }
    }
    val sentPublishMessagesByPeer by lazy {
        publishMessages.groupBy { it.origMsg.sendingPeer }
    }

    fun slice(startTime: Long, endTime: Long): GossipMessageResult =
        GossipMessageResult(
            messages.filter { it.sendTime in (startTime until endTime) },
            msgGenerator,
            gossipMessageIdGenerator
        )

    fun findPubMessagePath(peer: SimPeer, msgId: Long): List<PubMessageWrapper> {
        val ret = mutableListOf<PubMessageWrapper>()
        var curPeer = peer
        while (true) {
            val msg = findPubMessageFirst(curPeer, msgId)
                ?: break
            ret += msg
            curPeer = msg.origMsg.sendingPeer
        }
        return ret.reversed()
    }

    fun findPubMessageFirst(peer: SimPeer, msgId: Long): PubMessageWrapper? =
        publishMessages
            .filter { it.origMsg.receivingPeer === peer && it.simMsgId == msgId }
            .minByOrNull { it.origMsg.receiveTime }

    fun getPeerGossipMessages(peer: SimPeer) =
        allGossipMessages
            .filter { peer in setOf(it.origMsg.sendingPeer, it.origMsg.receivingPeer) }
            .sortedBy { if (it.origMsg.sendingPeer == peer) it.origMsg.sendTime else it.origMsg.receiveTime }

    fun getPeerMessages(peer: SimPeer) =
        messages
            .filter { peer in setOf(it.sendingPeer, it.receivingPeer) }
            .sortedBy { if (it.sendingPeer == peer) it.sendTime else it.receiveTime }

    fun getTotalTraffic() = messages
        .sumOf { msgGenerator.sizeEstimator(it.message) }

    fun getTotalMessageCount() = messages.size

    fun getGossipPubDeliveryResult(): GossipPubDeliveryResult {
        val fastestReceives = receivedPublishMessagesByPeerFastest.values.flatten()
        val orinatingMessages = originatingPublishMessages.mapValues { (_, msg) ->
            GossipPubDeliveryResult.MessagePublish(
                msg.simMsgId,
                msg.origMsg.sendingPeer as GossipSimPeer,
                msg.origMsg.sendTime
            )
        }

        return fastestReceives.map { receivedMsg ->
            val simMsgId = receivedMsg.simMsgId
            val origMessage = orinatingMessages[simMsgId]
                ?: throw IllegalStateException("No originating message with id $simMsgId found")
            GossipPubDeliveryResult.MessageDelivery(
                origMessage,
                receivedMsg.origMsg.receivingPeer as GossipSimPeer,
                receivedMsg.origMsg.receiveTime
            )
        }.let { GossipPubDeliveryResult(it) }
    }
}
