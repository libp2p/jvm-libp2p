package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.LimitedList
import pubsub.pb.Rpc

private data class CacheEntry(val msgId: String, val topics: Set<String>)

class MCache(val gossipSize: Int, historyLength: Int) {

    private val messages = mutableMapOf<String, Rpc.Message>()
    private val history = LimitedList<MutableList<CacheEntry>>(historyLength)
        .also { it.add(mutableListOf()) }
        .also { it.onDrop { it.forEach { messages.remove(it.msgId) } } }

    fun put(msgId: String, msg: Rpc.Message) {
        messages[msgId] = msg
        history.last.add(CacheEntry(msgId, msg.topicIDsList.toSet()))
    }

    fun getMessage(msgId: String) = messages[msgId]
    fun getMessageIds(topic: String) =
        history.takeLast(gossipSize).flatten().filter { topic in it.topics }.map { it.msgId }.distinct()

    fun shift() = history.add(mutableListOf())
}