package io.libp2p.simulate.stats.collect.gossip

import com.google.protobuf.AbstractMessage
import io.libp2p.etc.types.toWBytes
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.gossip.GossipPubMessageGenerator
import io.libp2p.simulate.stats.collect.CollectedMessage
import pubsub.pb.Rpc

class GossipMessageResult(
    val messages: List<CollectedMessage<Rpc.RPC>>,
    private val msgGenerator: GossipPubMessageGenerator
) {

    interface MessageWrapper<TMessage> {
        val origMsg: CollectedMessage<TMessage>
    }

    data class GenericMessageWrapper<TMessage : AbstractMessage>(
        override val origMsg: CollectedMessage<TMessage>,
    ) : MessageWrapper<TMessage> {
        override fun toString() = "GenericMessageWrapper[$origMsg, ${origMsg.message}]"
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
    ) : MessageWrapper<Rpc.ControlIHave> {
        override fun toString() = "IHave[$origMsg, messageIds: [" +
            "${origMsg.message.messageIDsList.map { it.toWBytes() }.joinToString(", ")}]]"
    }

    data class IWantMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.ControlIWant>,
    ) : MessageWrapper<Rpc.ControlIWant> {
        override fun toString() = "IWant[$origMsg, messageIds: [" +
            "${origMsg.message.messageIDsList.map { it.toWBytes() }.joinToString(", ")}]]"
    }

    data class PubMessageWrapper(
        override val origMsg: CollectedMessage<Rpc.Message>,
        val msgId: Long,
    ) : MessageWrapper<Rpc.Message>

    val publishMessages by lazy {
        messages
            .flatMap { collMsg ->
                collMsg.message.publishList.map { pubMsg ->
                    val origMsg = collMsg.withMessage(pubMsg)
                    PubMessageWrapper(
                        origMsg,
                        msgGenerator.messageIdRetriever(origMsg.message.data.toByteArray()),
                    )
                }
            }
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
        flattenControl({ it.ihaveList }, { IHaveMessageWrapper(it) })
    }
    val iWantMessages by lazy {
        flattenControl({ it.iwantList }, { IWantMessageWrapper(it) })
    }

    val allGossipMessages by lazy {
        (publishMessages + graftMessages + pruneMessages + iHaveMessages + iWantMessages)
            .sortedBy { it.origMsg.sendTime }
    }

    val distinctPublishMessages by lazy {
        publishMessages
            .map { it.origMsg.message }
            .distinct()
    }

    val receivedPublishMessagesByPeer by lazy {
        publishMessages.groupBy { it.origMsg.receivingPeer }
    }
    val sentPublishMessagesByPeer by lazy {
        publishMessages.groupBy { it.origMsg.sendingPeer }
    }
    val firstReceivedPublishMessagesByPeer by lazy {
        receivedPublishMessagesByPeer.mapValues { (_, msgs) ->
            msgs
                .groupBy { it.msgId }
                .values
                .map { idMsgs ->
                    idMsgs.minByOrNull { it.origMsg.receiveTime }
                }
                .filterNotNull()
        }
    }

    fun slice(startTime: Long, endTime: Long): GossipMessageResult =
        GossipMessageResult(messages.filter { it.sendTime in (startTime until endTime) }, msgGenerator)

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
            .filter { it.origMsg.receivingPeer === peer && it.msgId == msgId }
            .minByOrNull { it.origMsg.receiveTime }

    fun getPeerGossipMessages(peer: SimPeer) =
        allGossipMessages
            .filter { peer in setOf(it.origMsg.sendingPeer, it.origMsg.receivingPeer) }
            .sortedBy { if (it.origMsg.sendingPeer == peer) it.origMsg.sendTime else it.origMsg.receiveTime }

    fun getPeerMessages(peer: SimPeer) =
        messages
            .filter { peer in setOf(it.sendingPeer, it.receivingPeer) }
            .sortedBy { if (it.sendingPeer == peer) it.sendTime else it.receiveTime }
}
