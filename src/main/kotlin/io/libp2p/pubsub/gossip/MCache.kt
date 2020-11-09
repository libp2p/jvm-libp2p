package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.LimitedList
import io.libp2p.pubsub.MessageId
import pubsub.pb.Rpc
import java.util.concurrent.atomic.AtomicInteger

private data class CacheEntry(val msgId: MessageId, val topics: Set<Topic>)

data class MessageForPeer(val msg: Rpc.Message, val sentCount: Int)

class MCache(val gossipSize: Int, historyLength: Int) {

    private val messages = mutableMapOf<MessageId, Rpc.Message>()
    private val peerRequests = mutableMapOf<MessageId, MutableMap<PeerId, AtomicInteger>>()
    private val history = LimitedList<MutableList<CacheEntry>>(historyLength)
        .also { it.add(mutableListOf()) }
        .also {
            it.onDrop {
                it.forEach {
                    messages -= it.msgId
                    peerRequests -= it.msgId
                }
            }
        }

    fun put(msgId: MessageId, msg: Rpc.Message) {
        messages[msgId] = msg
        history.last.add(CacheEntry(msgId, msg.topicIDsList.toSet()))
    }

    fun getMessage(msgId: MessageId) = messages[msgId]
    fun getMessageForPeer(peer: PeerId, msgId: MessageId) = messages[msgId]?.let {
        val sentCount = peerRequests
            .computeIfAbsent(msgId) { mutableMapOf() }
            .computeIfAbsent(peer) { AtomicInteger() }
            .getAndIncrement()
        MessageForPeer(it, sentCount)
    }

    fun getMessageIds(topic: Topic) =
        history.takeLast(gossipSize).flatten().filter { topic in it.topics }.map { it.msgId }.distinct()

    fun shift() = history.add(mutableListOf())
}
