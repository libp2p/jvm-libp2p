package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.LimitedList
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import java.util.concurrent.atomic.AtomicInteger

private data class CacheEntry(val msgId: MessageId, val topics: Set<Topic>)

data class MessageForPeer(val msg: PubsubMessage, val sentCount: Int)

/**
 * Message cache owned by [GossipRouter]'s event executor. It is not thread-safe.
 */
class MCache(val gossipSize: Int, historyLength: Int) {

    private val messages = mutableMapOf<MessageId, PubsubMessage>()
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

    fun add(msg: PubsubMessage) {
        messages[msg.messageId] = msg
        history.last().add(CacheEntry(msg.messageId, msg.topics.toSet()))
    }

    operator fun get(msgId: MessageId) = messages[msgId]
    fun getMessageForPeer(peer: PeerId, msgId: MessageId) = messages[msgId]?.let {
        val sentCount = peerRequests
            .computeIfAbsent(msgId) { mutableMapOf() }
            .computeIfAbsent(peer) { AtomicInteger() }
            .getAndIncrement()
        MessageForPeer(it, sentCount)
    }

    fun getGossipMessageIdsByTopic(): Map<Topic, Set<MessageId>> {
        val messageIdsByTopic = mutableMapOf<Topic, MutableSet<MessageId>>()
        val startIndex = (history.size - gossipSize).coerceAtLeast(0)
        val gossipWindows = history.listIterator(startIndex)

        while (gossipWindows.hasNext()) {
            gossipWindows.next().forEach { entry ->
                entry.topics.forEach { topic ->
                    messageIdsByTopic.getOrPut(topic) { linkedSetOf() }.add(entry.msgId)
                }
            }
        }

        return messageIdsByTopic
    }

    fun shift() = history.add(mutableListOf())

    operator fun plusAssign(msg: PubsubMessage) = add(msg)
}
