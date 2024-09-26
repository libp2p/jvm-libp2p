package io.libp2p.pubsub

import io.libp2p.etc.types.contains
import io.libp2p.etc.types.get
import io.libp2p.etc.types.mutableBiMultiMap
import io.libp2p.etc.types.set
import java.time.Duration
import java.util.LinkedList

/**
 * The interface which is very similar to `Map<PubsubMessage, TValue>`
 * (and can behave as a regular `Map` see [SimpleSeenCache] for example)
 * Though the 'key' [PubsubMessage] may be handled slightly differently here in the sense
 * its [PubsubMessage.messageId] can be accessed lazily (see [FastIdSeenCache] for example)
 * and thus [PubsubMessage.hashCode] can't be calculated as for regular `Map`
 * In other words the 'key' [PubsubMessage] is rather 'matched' here then checked for equity
 */
interface SeenCache<TValue> {
    val size: Int

    fun put(msg: PubsubMessage, value: TValue)
    fun get(msg: PubsubMessage): TValue?
    fun isSeen(msg: PubsubMessage): Boolean
    fun isSeen(messageId: MessageId): Boolean
    fun remove(messageId: MessageId)

    /**
     * Returns the 'matching' message if it exists in the cache or falls back to returning the argument if not
     * The returned instance may have some data prepared and cached (e.g. `messageId`) which may
     * have positive performance effect
     */
    fun getSeenMessageCached(msg: PubsubMessage): PubsubMessage
}

operator fun <TValue> SeenCache<TValue>.get(msg: PubsubMessage) = get(msg)
operator fun <TValue> SeenCache<TValue>.set(msg: PubsubMessage, value: TValue) = put(msg, value)
operator fun <TValue> SeenCache<TValue>.contains(msg: PubsubMessage) = isSeen(msg)
operator fun <TValue> SeenCache<TValue>.minusAssign(messageId: MessageId) = remove(messageId)

class SimpleSeenCache<TValue> : SeenCache<TValue> {
    private val map: MutableMap<MessageId, TValue> = mutableMapOf()

    override val size: Int
        get() = map.size

    override fun getSeenMessageCached(msg: PubsubMessage) = msg
    override fun get(msg: PubsubMessage) = map[msg.messageId]
    override fun isSeen(msg: PubsubMessage) = msg.messageId in map
    override fun isSeen(messageId: MessageId) = messageId in map

    override fun put(msg: PubsubMessage, value: TValue) {
        map[msg.messageId] = value
    }
    override fun remove(messageId: MessageId) {
        map -= messageId
    }
}

class LRUSeenCache<TValue>(val delegate: SeenCache<TValue>, private val maxSize: Int) : SeenCache<TValue> by delegate {
    val evictingQueue = LinkedList<MessageId>()

    override fun put(msg: PubsubMessage, value: TValue) {
        val oldSize = delegate.size
        delegate[msg] = value
        if (oldSize < delegate.size) {
            evictingQueue += msg.messageId
            if (evictingQueue.size > maxSize) {
                delegate -= evictingQueue.removeFirst()
            }
        }
    }

    override fun remove(messageId: MessageId) {
        delegate -= messageId
        evictingQueue -= messageId
    }
}

class TTLSeenCache<TValue>(
    val delegate: SeenCache<TValue>,
    private val ttl: Duration,
    private val curTime: () -> Long
) : SeenCache<TValue> by delegate {

    data class TimedMessage(val time: Long, val messageId: MessageId)

    val putTimes = LinkedList<TimedMessage>()

    override fun put(msg: PubsubMessage, value: TValue) {
        delegate[msg] = value
        putTimes += TimedMessage(curTime(), msg.messageId)
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
            delegate -= n.messageId
            it.remove()
        }
    }
}

class FastIdSeenCache<TValue>(private val fastIdFunction: (PubsubMessage) -> Any) : SeenCache<TValue> {
    val fastIdMap = mutableBiMultiMap<Any, MessageId>()
    val slowIdMap: MutableMap<MessageId, TValue> = mutableMapOf()

    override val size: Int
        get() = slowIdMap.size

    override fun getSeenMessageCached(msg: PubsubMessage): PubsubMessage {
        val slowId = fastIdMap[fastIdFunction(msg)]
        return when {
            slowId == null -> msg
            msg is FastIdPubsubMessage -> msg
            else -> FastIdPubsubMessage(msg, slowId)
        }
    }

    override fun get(msg: PubsubMessage): TValue? {
        val slowId = fastIdMap[fastIdFunction(msg)] ?: msg.messageId
        return slowIdMap[slowId]
    }

    override fun isSeen(msg: PubsubMessage) =
        fastIdFunction(msg) in fastIdMap || msg.messageId in slowIdMap

    override fun isSeen(messageId: MessageId) = messageId in slowIdMap

    override fun put(msg: PubsubMessage, value: TValue) {
        fastIdMap[fastIdFunction(msg)] = msg.messageId
        slowIdMap[msg.messageId] = value
    }

    override fun remove(messageId: MessageId) {
        slowIdMap -= messageId
        fastIdMap.removeAllByValue(messageId)
    }

    /**
     * Wraps [delegate] instance and overrides [messageId] with a cached value
     * to avoid slow `messageId` computation by the [delegate] instance
     */
    private class FastIdPubsubMessage(
        val delegate: PubsubMessage,
        override val messageId: MessageId
    ) : PubsubMessage by delegate
}
