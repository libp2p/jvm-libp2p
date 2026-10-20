package io.libp2p.pubsub

import io.libp2p.etc.types.contains
import io.libp2p.etc.types.get
import io.libp2p.etc.types.set
import java.time.Duration
import java.util.ArrayDeque
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

    /**
     * Single lookup combining [get] and [getSeenMessageCached]: returns the cached message together
     * with its value, or `null` if the message was not seen.
     * Implementations which need an expensive key per lookup should override this to pay it once.
     */
    fun getSeen(msg: PubsubMessage): SeenEntry<TValue>? =
        get(msg)?.let { SimpleSeenEntry(getSeenMessageCached(msg), it) }
}

/**
 * A seen message together with the value stored for it, as returned by [SeenCache.getSeen]
 */
interface SeenEntry<TValue> {
    val message: PubsubMessage
    val value: TValue
}

private class SimpleSeenEntry<TValue>(
    override val message: PubsubMessage,
    override val value: TValue
) : SeenEntry<TValue>

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

    /**
     * One entry per message in the [delegate], in put order. A message which is put again while
     * still present keeps its original time: re-putting (e.g. storing the validation result) must
     * neither extend its TTL nor queue a second, useless removal.
     */
    val putTimes = ArrayDeque<TimedMessage>()

    override fun put(msg: PubsubMessage, value: TValue) {
        val oldSize = delegate.size
        delegate[msg] = value
        if (delegate.size > oldSize) {
            putTimes += TimedMessage(curTime(), msg.messageId)
        }
        pruneOld()
    }

    private fun pruneOld() {
        val pruneBefore = curTime() - ttl.toMillis()
        while (true) {
            val oldest = putTimes.peekFirst() ?: break
            if (oldest.time >= pruneBefore) {
                break
            }
            putTimes.pollFirst()
            delegate -= oldest.messageId
        }
    }
}

/**
 * Keeps the seen messages under two keys: the cheap `fastId` computed by [fastIdFunction] from the
 * message instance (e.g. a digest of the wire bytes) and the canonical [PubsubMessage.messageId],
 * which may be expensive to derive. Both maps point at the same [Entry], so a lookup by fast id
 * (the hot path: every duplicate copy of a message) costs a single map probe and never touches the
 * slow id, while removal by message id (TTL/LRU eviction) still finds every fast id of the message.
 */
class FastIdSeenCache<TValue>(private val fastIdFunction: (PubsubMessage) -> Any) : SeenCache<TValue> {

    /**
     * A seen message: its canonical id, the value stored for it and the fast id(s) under which
     * [fastIdMap] currently points at it. A message normally has exactly one fast id, kept in
     * [fastId]; [otherFastIds] only exists when the same message was put under different fast ids.
     * [fastId] is null once another message has taken that id over.
     */
    class Entry<TValue> internal constructor(
        val messageId: MessageId,
        internal var fastId: Any?,
        internal var value: TValue
    ) {
        internal var otherFastIds: MutableList<Any>? = null

        internal fun hasFastId(id: Any) = fastId == id || otherFastIds?.contains(id) == true

        internal fun addFastId(id: Any) {
            if (fastId == null) {
                fastId = id
            } else {
                (otherFastIds ?: mutableListOf<Any>().also { otherFastIds = it }) += id
            }
        }

        /** Forgets [id]: [fastIdMap] no longer points at this entry under it */
        internal fun removeFastId(id: Any) {
            if (fastId == id) {
                fastId = null
            } else {
                otherFastIds?.remove(id)
            }
        }
    }

    val fastIdMap = HashMap<Any, Entry<TValue>>()
    val slowIdMap = HashMap<MessageId, Entry<TValue>>()

    override val size: Int
        get() = slowIdMap.size

    private fun findEntry(msg: PubsubMessage): Entry<TValue>? =
        fastIdMap[fastIdFunction(msg)] ?: slowIdMap[msg.messageId]

    override fun getSeenMessageCached(msg: PubsubMessage): PubsubMessage {
        val entry = fastIdMap[fastIdFunction(msg)]
        return when {
            entry == null -> msg
            msg is FastIdPubsubMessage<*> && msg.entry === entry -> msg
            else -> FastIdPubsubMessage(msg, entry)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun getSeen(msg: PubsubMessage): SeenEntry<TValue>? {
        val entry = findEntry(msg) ?: return null
        return if (msg is FastIdPubsubMessage<*> && msg.entry === entry) {
            msg as SeenEntry<TValue>
        } else {
            FastIdPubsubMessage(msg, entry)
        }
    }

    override fun get(msg: PubsubMessage): TValue? = findEntry(msg)?.value

    override fun isSeen(msg: PubsubMessage) =
        fastIdFunction(msg) in fastIdMap || msg.messageId in slowIdMap

    override fun isSeen(messageId: MessageId) = messageId in slowIdMap

    override fun put(msg: PubsubMessage, value: TValue) {
        val fastId = fastIdFunction(msg)
        val messageId = msg.messageId
        val fresh = Entry(messageId, fastId, value)
        val entry = slowIdMap.computeIfAbsent(messageId) { fresh }
        if (entry === fresh) {
            fastIdMap.put(fastId, entry)?.removeFastId(fastId)
        } else {
            entry.value = value
            if (!entry.hasFastId(fastId)) {
                entry.addFastId(fastId)
                fastIdMap.put(fastId, entry)?.removeFastId(fastId)
            }
        }
    }

    override fun remove(messageId: MessageId) {
        val entry = slowIdMap.remove(messageId) ?: return
        entry.fastId?.let { fastIdMap.remove(it, entry) }
        entry.otherFastIds?.forEach { fastIdMap.remove(it, entry) }
    }

    /**
     * Wraps [delegate] instance and overrides [messageId] with the cached value
     * to avoid slow `messageId` computation by the [delegate] instance
     */
    private class FastIdPubsubMessage<TValue>(
        val delegate: PubsubMessage,
        val entry: Entry<TValue>
    ) : PubsubMessage by delegate, SeenEntry<TValue> {
        override val messageId: MessageId get() = entry.messageId
        override val message: PubsubMessage get() = this
        override val value: TValue get() = entry.value
    }
}
