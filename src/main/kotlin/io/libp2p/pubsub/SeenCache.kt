package io.libp2p.pubsub

import io.libp2p.etc.types.contains
import io.libp2p.etc.types.get
import io.libp2p.etc.types.mutableBiMultiMap
import io.libp2p.etc.types.set
import java.time.Duration
import java.util.LinkedList

/**
 * The interface which is very similar to `Map<PubsubMessage, TValue>`
 * (and can behave as a regular `Map`see [SimpleSeenCache] for example)
 * Though the 'key' [PubsubMessage] may be handled slightly differently here in the sense
 * its [PubsubMessage.messageId] can be accessed lazily (see [FastIdSeenCache] for example)
 * and thus [PubsubMessage.hashCode] can't be calculated as for regular `Map`
 * In other words the 'key' [PubsubMessage] is rather 'matched' here then checked for equity
 */
interface SeenCache<TValue> {
    val size: Int
    val messages: Collection<PubsubMessage>

    /**
     * Returns the 'matching' key if it already exist in the cache or returns the argument if not
     */
    fun getSeenMessage(msg: PubsubMessage): PubsubMessage
    fun getValue(msg: PubsubMessage): TValue?
    fun isSeen(msg: PubsubMessage): Boolean
    fun isSeen(messageId: MessageId): Boolean
    fun put(msg: PubsubMessage, value: TValue)
    fun remove(msg: PubsubMessage)
}

operator fun <TValue> SeenCache<TValue>.get(msg: PubsubMessage) = getValue(msg)
operator fun <TValue> SeenCache<TValue>.set(msg: PubsubMessage, value: TValue) = put(msg, value)
operator fun <TValue> SeenCache<TValue>.contains(msg: PubsubMessage) = isSeen(msg)
operator fun <TValue> SeenCache<TValue>.minusAssign(msg: PubsubMessage) = remove(msg)

class SimpleSeenCache<TValue> : SeenCache<TValue> {
    private val map: MutableMap<MessageId, Pair<PubsubMessage, TValue>> = mutableMapOf()

    override val size: Int
        get() = map.size
    override val messages: Collection<PubsubMessage>
        get() = map.values.map { it.first }

    override fun getSeenMessage(msg: PubsubMessage) = msg
    override fun getValue(msg: PubsubMessage) = map[msg.messageId]?.second
    override fun isSeen(msg: PubsubMessage) = msg.messageId in map
    override fun isSeen(messageId: MessageId) = messageId in map

    override fun put(msg: PubsubMessage, value: TValue) {
        map[msg.messageId] = msg to value
    }
    override fun remove(msg: PubsubMessage) { map -= msg.messageId }
}

class LRUSeenCache<TValue>(val delegate: SeenCache<TValue>, private val maxSize: Int) : SeenCache<TValue> by delegate {
    val evictingQueue = LinkedList<PubsubMessage>()

    override fun put(msg: PubsubMessage, value: TValue) {
        val oldSize = delegate.size
        delegate[msg] = value
        if (oldSize < delegate.size) {
            evictingQueue += msg
            if (evictingQueue.size > maxSize) {
                delegate -= evictingQueue.removeFirst()
            }
        }
    }

    override fun remove(msg: PubsubMessage) {
        delegate -= msg
        evictingQueue -= msg
    }
}

class TTLSeenCache<TValue>(
    val delegate: SeenCache<TValue>,
    private val ttl: Duration,
    private val curTime: () -> Long
) : SeenCache<TValue> by delegate {

    data class TimedMessage(val time: Long, val message: PubsubMessage)

    val putTimes = LinkedList<TimedMessage>()

    override fun put(msg: PubsubMessage, value: TValue) {
        delegate[msg] = value
        putTimes += TimedMessage(curTime(), msg)
        pruneOld()
    }

    private fun pruneOld() {
        val pruneBefore = curTime() - ttl.toMillis()
        val it = putTimes.iterator()
        while (it.hasNext()) {
            val n = it.next()
            if (n.time >= pruneBefore) {
                break
            }
            delegate -= n.message
            it.remove()
        }
    }
}

class FastIdSeenCache<TValue>(private val fastIdFunction: (PubsubMessage) -> Any) : SeenCache<TValue> {
    val fastIdMap = mutableBiMultiMap<Any, MessageId>()
    val slowIdMap: MutableMap<MessageId, Pair<PubsubMessage, TValue>> = mutableMapOf()

    override val size: Int
        get() = slowIdMap.size
    override val messages: Collection<PubsubMessage>
        get() = slowIdMap.values.map { it.first }

    override fun getSeenMessage(msg: PubsubMessage): PubsubMessage {
        val slowId = fastIdMap[fastIdFunction(msg)]
        return if (slowId == null) msg else slowIdMap[slowId]!!.first
    }

    override fun getValue(msg: PubsubMessage): TValue? {
        val slowId = fastIdMap[fastIdFunction(msg)] ?: msg.messageId
        return slowIdMap[slowId]?.second
    }

    override fun isSeen(msg: PubsubMessage) =
        fastIdFunction(msg) in fastIdMap || msg.messageId in slowIdMap
    override fun isSeen(messageId: MessageId) = messageId in slowIdMap

    override fun put(msg: PubsubMessage, value: TValue) {
        fastIdMap[fastIdFunction(msg)] = msg.messageId
        slowIdMap[msg.messageId] = msg to value
    }

    override fun remove(msg: PubsubMessage) {
        val slowId = msg.messageId
        slowIdMap -= slowId
        fastIdMap.removeAllByValue(slowId)
    }
}
