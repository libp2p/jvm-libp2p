package io.libp2p.simulate.stats.collect.gossip

import com.google.protobuf.AbstractMessage
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.gossip.GossipPubMessageGenerator
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
        val simMsgId: List<SimMessageId?>
    ) : MessageWrapper<Rpc.ControlIHave> {
        override fun toString() = "IHave[$origMsg, messageIds: $simMsgId]"
    }

    data class IWantMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlIWant>,
        val simMsgIds: List<SimMessageId?>
    ) : MessageWrapper<Rpc.ControlIWant> {
        override fun toString() = "IWant[$origMsg, messageIds: $simMsgIds]"
    }

    data class ChokeMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlChoke>,
    ) : MessageWrapper<Rpc.ControlChoke> {
        override fun toString() = "Choke[$origMsg, topicId: '${origMsg.message.topicID}']"
    }

    data class UnChokeMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlUnChoke>,
    ) : MessageWrapper<Rpc.ControlUnChoke> {
        override fun toString() = "Unchoke[$origMsg, topicId: '${origMsg.message.topicID}']"
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
            .mapValues { (_, pubMessages) ->
                pubMessages.first()
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
                    gossipMessageIdToSimMessageIdMap[it.toWBytes()]
                }
            )
        })
    }
    val iWantMessages by lazy {
        flattenControl({ it.iwantList }, {
            IWantMessageWrapper(
                it,
                it.message.messageIDsList.map {
                    gossipMessageIdToSimMessageIdMap[it.toWBytes()]
                }
            )
        })
    }
    val chokeMessages by lazy {
        flattenControl({ it.chokeList }, { ChokeMessageWrapper(it) })
    }
    val unchokeMessages by lazy {
        flattenControl({ it.unchokeList }, { UnChokeMessageWrapper(it) })
    }

    val allGossipMessages by lazy {
        (publishMessages + graftMessages + pruneMessages + iHaveMessages + iWantMessages + chokeMessages + unchokeMessages)
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

    val allPeers by lazy {
        messages.flatMap { listOf(it.sendingPeer, it.receivingPeer) }.toSet()
    }

    fun slice(startTime: Long, endTime: Long = Long.MAX_VALUE): GossipMessageResult =
        GossipMessageResult(
            messages.filter { it.sendTime in (startTime until endTime) },
            msgGenerator,
            gossipMessageIdGenerator
        )

    fun <K> groupBy(keySelectror: (CollectedMessage<Rpc.RPC>) -> K): Map<K, GossipMessageResult> =
        messages
            .groupBy { keySelectror(it) }
            .mapValues { GossipMessageResult(it.value, msgGenerator, gossipMessageIdGenerator) }

    fun filter(predicate: (CollectedMessage<Rpc.RPC>) -> Boolean): GossipMessageResult =
        messages
            .filter(predicate)
            .let { GossipMessageResult(it, msgGenerator, gossipMessageIdGenerator) }

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

    fun isByIWantPubMessage(msg: PubMessageWrapper): Boolean =
        iWantMessages.any { iWantMsg ->
            iWantMsg.origMsg.sendingPeer == msg.origMsg.receivingPeer &&
                    iWantMsg.origMsg.receivingPeer == msg.origMsg.sendingPeer &&
                    msg.simMsgId in iWantMsg.simMsgIds &&
                    iWantMsg.origMsg.receiveTime <= msg.origMsg.sendTime
        }


    fun getTotalTraffic() = messages
        .sumOf { msgGenerator.sizeEstimator(it.message) }

    fun getTotalMessageCount() = messages.size
}
