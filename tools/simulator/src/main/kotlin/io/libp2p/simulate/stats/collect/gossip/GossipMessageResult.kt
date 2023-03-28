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

data class GossipMessageResult(
    val messages: List<CollectedMessage<Rpc.RPC>>,
    private val msgGenerator: GossipPubMessageGenerator,
    private val gossipMessageIdGenerator: GossipMessageIdGenerator,
    private val gossipMessageIdToSimMessageIdHint: Map<GossipMessageId, SimMessageId> = emptyMap()
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

    data class ChokeMessageMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlChokeMessage>,
        val simMsgId: SimMessageId?
    ) : MessageWrapper<Rpc.ControlChokeMessage> {
        override fun toString() = "ChokeMessage[$origMsg, topicId: '$simMsgId']"
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
        publishMessages.associate { it.gossipMsgId to it.simMsgId } + gossipMessageIdToSimMessageIdHint
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
    val chokeMessageMessages by lazy {
        flattenControl({ it.chokeMessageList }, {
            ChokeMessageMessageWrapper(
                it,
                gossipMessageIdToSimMessageIdMap[it.message.messageID.toWBytes()]
            )
        })
    }

    val allGossipMessages by lazy {
        (publishMessages + graftMessages + pruneMessages +
                iHaveMessages + iWantMessages +
                chokeMessages + unchokeMessages +
                chokeMessageMessages)
            .sortedBy { it.origMsg.sendTime }
    }

    val peerReceivedMessages by lazy {
        messages.groupBy { it.receivingPeer }
            .mapValues { (_, msgs) ->
                copyWithMessages(msgs)
            }
    }
    val peerSentMessages by lazy {
        messages.groupBy { it.sendingPeer }
            .mapValues { (_, msgs) ->
                copyWithMessages(msgs)
            }
    }

    val allPeers by lazy {
        peerSentMessages.keys + peerReceivedMessages.keys
    }
    val allPeersById by lazy {
        allPeers.associateBy { it.simPeerId }
    }

    fun slice(startTime: Long, endTime: Long = Long.MAX_VALUE): GossipMessageResult =
        copyWithMessages(
            messages.filter { it.sendTime in (startTime until endTime) }
        )

    fun <K> groupBy(keySelectror: (CollectedMessage<Rpc.RPC>) -> K): Map<K, GossipMessageResult> =
        messages
            .groupBy { keySelectror(it) }
            .mapValues { copyWithMessages(it.value) }

    fun filter(predicate: (CollectedMessage<Rpc.RPC>) -> Boolean): GossipMessageResult =
        messages
            .filter(predicate)
            .let { copyWithMessages(it) }

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
        getPeerMessages(peer).allGossipMessages

    fun getPeerMessages(peer: SimPeer) =
        ((peerReceivedMessages[peer]?.messages ?: emptyList()) +
                (peerSentMessages[peer]?.messages ?: emptyList()))
            .sortedBy { if (it.sendingPeer == peer) it.sendTime else it.receiveTime }
            .let { copyWithMessages(it) }

    fun getConnectionMessages(peer1: SimPeer, peer2: SimPeer) =
        getPeerMessages(peer1)
            .getPeerMessages(peer2)

    fun getIWantsForPubMessage(msg: PubMessageWrapper) =
        peerSentMessages[msg.origMsg.receivingPeer]!!
            .iWantMessages
            .filter { iWantMsg ->
                iWantMsg.origMsg.receivingPeer == msg.origMsg.sendingPeer &&
                        msg.simMsgId in iWantMsg.simMsgIds &&
                        iWantMsg.origMsg.receiveTime <= msg.origMsg.sendTime
            }

    fun isByIWantPubMessage(msg: PubMessageWrapper): Boolean =
        getIWantsForPubMessage(msg).isNotEmpty()


    fun getTotalTraffic() = messages
        .sumOf { msgGenerator.sizeEstimator(it.message) }

    fun getTotalMessageCount() = messages.size

    private fun copyWithMessages(messages: List<CollectedMessage<Rpc.RPC>>) =
        this.copy(messages = messages, gossipMessageIdToSimMessageIdHint = gossipMessageIdToSimMessageIdMap)
}
