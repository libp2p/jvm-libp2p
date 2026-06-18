package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.LimitedList
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import java.util.concurrent.atomic.AtomicInteger

private data class CacheEntry(val msgId: MessageId, val topics: Set<Topic>)

data class MessageForPeer(val msg: PubsubMessage, val sentCount: Int)

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

    // Within-heartbeat memo: the last [gossipSize] history windows flattened into one list.
    // getMessageIds() is called once per topic, only from emitGossip(), which runs only inside
    // heartbeat(). heartbeat() runs as a single task on the gossip event thread, so no add()/
    // shift() can interleave between its per-topic queries — the first topic builds the snapshot
    // and the rest reuse it, turning ~(topic count) flattens per heartbeat into one. add()/shift()
    // invalidate it, but only between heartbeats (and at heartbeat end via shift()), when it is
    // not being read. Single-threaded access also makes this plain field safe, like the maps above.
    private var gossipHistorySnapshot: List<CacheEntry>? = null

    private fun gossipHistory(): List<CacheEntry> =
        gossipHistorySnapshot ?: history.takeLast(gossipSize).flatten()
            .also { gossipHistorySnapshot = it }

    fun add(msg: PubsubMessage) {
        messages[msg.messageId] = msg
        history.last().add(CacheEntry(msg.messageId, msg.topics.toSet()))
        gossipHistorySnapshot = null
    }

    operator fun get(msgId: MessageId) = messages[msgId]
    fun getMessageForPeer(peer: PeerId, msgId: MessageId) = messages[msgId]?.let {
        val sentCount = peerRequests
            .computeIfAbsent(msgId) { mutableMapOf() }
            .computeIfAbsent(peer) { AtomicInteger() }
            .getAndIncrement()
        MessageForPeer(it, sentCount)
    }

    fun getMessageIds(topic: Topic): List<MessageId> =
        gossipHistory().asSequence()
            .filter { topic in it.topics }
            .map { it.msgId }
            .distinct()
            .toList()

    fun shift() {
        history.add(mutableListOf())
        gossipHistorySnapshot = null
    }

    operator fun plusAssign(msg: PubsubMessage) = add(msg)
}
