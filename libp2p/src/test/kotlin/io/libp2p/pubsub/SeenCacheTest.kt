package io.libp2p.pubsub

import com.google.protobuf.ByteString
import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.hours
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets.US_ASCII
import java.util.concurrent.atomic.AtomicLong

fun createMessage(number: Int): Rpc.Message {
    return Rpc.Message.newBuilder()
        .addTopicIDs("topic")
        .setSeqno(number.toBytesBigEndian().toProtobuf())
        .setData(ByteString.copyFrom("Hello-$number", US_ASCII))
        .build()
}

fun createPubsubMessage(number: Int) = TestPubsubMessage(createMessage(number))
fun createPubsubMessage(number: Int, fastId: Int) =
    TestPubsubMessage(createMessage(number)).also { it.fastID = fastId }

class TestPubsubMessage(override val protobufMessage: Rpc.Message) : PubsubMessage {
    var canonicalIdCalculator: (Rpc.Message) -> WBytes = {
        WBytes(("canon-" + it.data.toString(US_ASCII)).toByteArray())
    }
    var canonicalId: WBytes? = null
    lateinit var fastID: Any
    override val messageId: WBytes
        get() {
            if (canonicalId == null) {
                canonicalId = canonicalIdCalculator(protobufMessage)
            }
            return canonicalId!!
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as TestPubsubMessage
        return protobufMessage == that.protobufMessage
    }

    override fun hashCode(): Int {
        return protobufMessage.hashCode()
    }
}

fun genericSanityTest(cache: SeenCache<String>) {
    assertThat(cache.size).isEqualTo(0)

    cache[createPubsubMessage(1)] = "1"

    assertThat(cache.size).isEqualTo(1)
    assertThat(cache.messages).containsExactly(createPubsubMessage(1))
    assertThat(cache.isSeen(createPubsubMessage(1))).isTrue()
    assertThat(cache.isSeen(createPubsubMessage(2))).isFalse()
    assertThat(cache.getValue(createPubsubMessage(1))).isEqualTo("1")
    assertThat(cache.getValue(createPubsubMessage(2))).isNull()
    assertThat(cache.getSeenMessage(createPubsubMessage(1))).isEqualTo(createPubsubMessage(1))

    cache[createPubsubMessage(1)] = "1-1"

    assertThat(cache.size).isEqualTo(1)
    assertThat(cache.messages).containsExactly(createPubsubMessage(1))
    assertThat(cache.isSeen(createPubsubMessage(1))).isTrue()
    assertThat(cache.isSeen(createPubsubMessage(2))).isFalse()
    assertThat(cache.getValue(createPubsubMessage(1))).isEqualTo("1-1")
    assertThat(cache.getValue(createPubsubMessage(2))).isNull()

    cache[createPubsubMessage(2)] = "2"

    assertThat(cache.size).isEqualTo(2)
    assertThat(cache.messages).containsExactly(createPubsubMessage(1), createPubsubMessage(2))
    assertThat(cache.isSeen(createPubsubMessage(1))).isTrue()
    assertThat(cache.isSeen(createPubsubMessage(2))).isTrue()
    assertThat(cache.getValue(createPubsubMessage(1))).isEqualTo("1-1")
    assertThat(cache.getValue(createPubsubMessage(2))).isEqualTo("2")

    cache -= createPubsubMessage(1)

    assertThat(cache.size).isEqualTo(1)
    assertThat(cache.messages).containsExactly(createPubsubMessage(2))
    assertThat(cache.isSeen(createPubsubMessage(1))).isFalse()
    assertThat(cache.isSeen(createPubsubMessage(2))).isTrue()
    assertThat(cache.getValue(createPubsubMessage(1))).isNull()
    assertThat(cache.getValue(createPubsubMessage(2))).isEqualTo("2")

    cache -= createPubsubMessage(2)

    assertThat(cache.size).isEqualTo(0)
    assertThat(cache.messages).isEmpty()
    assertThat(cache.isSeen(createPubsubMessage(1))).isFalse()
    assertThat(cache.isSeen(createPubsubMessage(2))).isFalse()
    assertThat(cache.getValue(createPubsubMessage(1))).isNull()
    assertThat(cache.getValue(createPubsubMessage(2))).isNull()
}

class LRUSeenCacheTest {

    @Test
    fun `sanity test`() {
        val backingCache = SimpleSeenCache<String>()
        val lruCache = LRUSeenCache(backingCache, 3)

        genericSanityTest(lruCache)

        assertThat(lruCache.evictingQueue).isEmpty()
        assertThat(backingCache.size).isEqualTo(0)
        assertThat(backingCache.messages).isEmpty()
    }

    @Test
    fun `test old entries are evicted`() {
        val backingCache = SimpleSeenCache<String>()
        val lruCache = LRUSeenCache(backingCache, 3)
        lruCache[createPubsubMessage(1)] = "1"
        lruCache[createPubsubMessage(2)] = "2"
        lruCache[createPubsubMessage(3)] = "3"

        assertThat(lruCache.size).isEqualTo(3)
        assertThat(lruCache.messages).containsExactly(
            createPubsubMessage(1),
            createPubsubMessage(2),
            createPubsubMessage(3)
        )

        lruCache[createPubsubMessage(4)] = "4"

        assertThat(lruCache.size).isEqualTo(3)
        assertThat(lruCache.messages).containsExactly(
            createPubsubMessage(2),
            createPubsubMessage(3),
            createPubsubMessage(4)
        )

        lruCache[createPubsubMessage(5)] = "5"

        assertThat(lruCache.size).isEqualTo(3)
        assertThat(lruCache.messages).containsExactly(
            createPubsubMessage(3),
            createPubsubMessage(4),
            createPubsubMessage(5)
        )

        lruCache[createPubsubMessage(1)] = "1"

        assertThat(lruCache.size).isEqualTo(3)
        assertThat(lruCache.messages).containsExactly(
            createPubsubMessage(4),
            createPubsubMessage(5),
            createPubsubMessage(1)
        )
        assertThat(backingCache.size).isEqualTo(3)
    }

    @Test
    fun `test remove handled correctly`() {
        val backingCache = SimpleSeenCache<String>()
        val lruCache = LRUSeenCache(backingCache, 3)
        lruCache[createPubsubMessage(1)] = "1"
        lruCache[createPubsubMessage(2)] = "2"
        lruCache[createPubsubMessage(3)] = "3"

        assertThat(lruCache.size).isEqualTo(3)

        lruCache -= createPubsubMessage(1)

        assertThat(lruCache.size).isEqualTo(2)

        lruCache[createPubsubMessage(4)] = "4"

        assertThat(lruCache.size).isEqualTo(3)

        lruCache[createPubsubMessage(5)] = "5"

        assertThat(lruCache.size).isEqualTo(3)

        lruCache -= createPubsubMessage(5)

        assertThat(lruCache.size).isEqualTo(2)

        lruCache[createPubsubMessage(6)] = "6"

        assertThat(lruCache.size).isEqualTo(3)
        assertThat(lruCache.messages).containsExactly(
            createPubsubMessage(3),
            createPubsubMessage(4),
            createPubsubMessage(6)
        )

        lruCache -= createPubsubMessage(3)
        lruCache -= createPubsubMessage(4)

        assertThat(lruCache.size).isEqualTo(1)

        lruCache[createPubsubMessage(7)] = "7"
        lruCache[createPubsubMessage(8)] = "8"

        assertThat(lruCache.size).isEqualTo(3)

        lruCache[createPubsubMessage(9)] = "9"

        assertThat(lruCache.size).isEqualTo(3)
        assertThat(lruCache.messages).containsExactly(
            createPubsubMessage(7),
            createPubsubMessage(8),
            createPubsubMessage(9)
        )

        lruCache -= createPubsubMessage(7)
        lruCache -= createPubsubMessage(8)
        lruCache -= createPubsubMessage(9)

        assertThat(lruCache.size).isEqualTo(0)
        assertThat(lruCache.messages).isEmpty()
        assertThat(lruCache.evictingQueue).isEmpty()
        assertThat(backingCache.size).isEqualTo(0)
        assertThat(backingCache.messages).isEmpty()
    }
}

class TTLSeenCacheTest {

    @Test
    fun `sanity test`() {
        val backingCache = SimpleSeenCache<String>()
        val ttlCache = TTLSeenCache(backingCache, 1.seconds, { 1 })

        genericSanityTest(ttlCache)
    }

    @Test
    fun `test old entries are evicted`() {
        val backingCache = SimpleSeenCache<String>()
        val time = AtomicLong()
        val ttlCache = TTLSeenCache(backingCache, 1.seconds, time::get)
        time.set(0)
        ttlCache[createPubsubMessage(1)] = "1"
        time.set(100)
        ttlCache[createPubsubMessage(2)] = "2"
        ttlCache[createPubsubMessage(3)] = "3"

        assertThat(ttlCache.size).isEqualTo(3)
        assertThat(ttlCache.messages).containsExactly(
            createPubsubMessage(1),
            createPubsubMessage(2),
            createPubsubMessage(3)
        )

        time.set(1001)
        ttlCache[createPubsubMessage(4)] = "4"

        assertThat(ttlCache.size).isEqualTo(3)
        assertThat(ttlCache.messages).containsExactly(
            createPubsubMessage(2),
            createPubsubMessage(3),
            createPubsubMessage(4)
        )

        time.set(1002)
        ttlCache[createPubsubMessage(5)] = "5"

        assertThat(ttlCache.size).isEqualTo(4)
        assertThat(ttlCache.messages).containsExactly(
            createPubsubMessage(2),
            createPubsubMessage(3),
            createPubsubMessage(4),
            createPubsubMessage(5)
        )

        time.set(1102)
        ttlCache[createPubsubMessage(1)] = "1"

        assertThat(ttlCache.size).isEqualTo(3)
        assertThat(ttlCache.messages).containsExactly(
            createPubsubMessage(4),
            createPubsubMessage(5),
            createPubsubMessage(1)
        )

        time.set(3000)
        ttlCache[createPubsubMessage(6)] = "6"

        assertThat(ttlCache.size).isEqualTo(1)
        assertThat(ttlCache.messages).containsExactly(
            createPubsubMessage(6)
        )
        assertThat(ttlCache.putTimes.size).isLessThan(2)
    }

    @Test
    fun `test remove handled correctly`() {
        val backingCache = SimpleSeenCache<String>()
        val time = AtomicLong()
        val ttlCache = TTLSeenCache(backingCache, 1.seconds, time::get)
        ttlCache[createPubsubMessage(1)] = "1"
        ttlCache[createPubsubMessage(2)] = "2"
        ttlCache[createPubsubMessage(3)] = "3"

        ttlCache -= createPubsubMessage(1)

        assertThat(ttlCache.size).isEqualTo(2)

        time.set(2000)
        ttlCache[createPubsubMessage(4)] = "4"

        assertThat(ttlCache.size).isEqualTo(1)

        ttlCache[createPubsubMessage(5)] = "5"

        assertThat(ttlCache.size).isEqualTo(2)

        ttlCache -= createPubsubMessage(5)

        assertThat(ttlCache.size).isEqualTo(1)

        time.set(4000)
        ttlCache[createPubsubMessage(6)] = "6"

        assertThat(ttlCache.size).isEqualTo(1)
        assertThat(ttlCache.messages).containsExactly(
            createPubsubMessage(6)
        )
        assertThat(ttlCache.putTimes.size).isLessThan(2)
    }

    @Test()
    fun `test large size not quadratic time`() {
        val backingCache = FastIdSeenCache<String> { it.protobufMessage.data }
        val time = AtomicLong()
        val ttlCache = TTLSeenCache(backingCache, 10.hours, time::get)
        Assertions.assertTimeout(10.seconds) {
            for (i in 0..100_000) {
                time.incrementAndGet()
                ttlCache[createPubsubMessage(i)] = "$i"
            }
        }

        time.set(10.hours.toMillis())

        Assertions.assertTimeout(10.seconds) {
            for (i in 100_000..200_000) {
                time.incrementAndGet()
                ttlCache[createPubsubMessage(i)] = "$i"
            }
        }

        val size = ttlCache.size
        for (i in 100_000..110_000) {
            ttlCache[createPubsubMessage(i)] = "$i"
        }
        assertThat(ttlCache.size).isEqualTo(size)
    }
}

class FastIdSeenCacheTest {

    @Test
    fun `sanity test`() {
        val cache = FastIdSeenCache<String> { it.protobufMessage.data }

        genericSanityTest(cache)

        assertThat(cache.fastIdMap.isEmpty()).isTrue()
        assertThat(cache.slowIdMap).isEmpty()
    }

    @Test
    fun `test slow id not calculated when the same fast id`() {
        val cache = FastIdSeenCache<String> { (it as TestPubsubMessage).fastID }
        val m1_1 = createPubsubMessage(1, 1)
        val m1_2 = createPubsubMessage(1, 1)

        cache[m1_1] = "1-1"
        assertThat(m1_1.canonicalId).isNotNull()
        assertThat(m1_2.canonicalId).isNull()

        val m1_3 = cache.getSeenMessage(m1_2) as TestPubsubMessage
        assertThat(m1_3.canonicalId).isEqualTo(m1_1.canonicalId)
        assertThat(m1_2.canonicalId).isNull()

        assertThat(m1_2 in cache).isTrue()
        assertThat(m1_2.canonicalId).isNull()

        assertThat(cache.getValue(m1_2)).isEqualTo("1-1")
        assertThat(m1_2.canonicalId).isNull()
    }

    @Test
    fun `test different fast id with same slow id`() {
        val cache = FastIdSeenCache<String> { (it as TestPubsubMessage).fastID }
        val m1_1 = createPubsubMessage(1, 1)
        val m1_2 = createPubsubMessage(1, 2)

        cache[m1_1] = "1-1"
        assertThat(m1_1 in cache).isTrue()
        assertThat(m1_2 in cache).isTrue()
        assertThat(cache.getValue(m1_1)).isEqualTo("1-1")
        assertThat(cache.getValue(m1_2)).isEqualTo("1-1")

        cache[m1_2] = "1-2"
        assertThat(m1_1 in cache).isTrue()
        assertThat(m1_2 in cache).isTrue()
        assertThat(cache.getValue(m1_1)).isEqualTo("1-2")
        assertThat(cache.getValue(m1_2)).isEqualTo("1-2")

        val m1_1_1 = createPubsubMessage(1, 1)
        val m1_2_1 = createPubsubMessage(1, 2)
        assertThat(m1_1_1 in cache).isTrue()
        assertThat(m1_2_1 in cache).isTrue()
        assertThat(m1_1_1.canonicalId).isNull()
        assertThat(m1_2_1.canonicalId).isNull()

        cache -= m1_1
        assertThat(m1_1 in cache).isFalse()
        assertThat(m1_2 in cache).isFalse()
        assertThat(cache.getValue(m1_1)).isNull()
        assertThat(cache.getValue(m1_2)).isNull()

        assertThat(cache.fastIdMap.isEmpty()).isTrue()
        assertThat(cache.slowIdMap).isEmpty()
    }
}
