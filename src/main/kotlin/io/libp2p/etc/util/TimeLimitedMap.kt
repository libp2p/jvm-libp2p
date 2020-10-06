package io.libp2p.etc.util

import java.time.Duration
import java.util.LinkedList

/**
 * Map where entries are purged after timeout from the moment when the key was initially added.
 *
 * NOT thread safe
 */
class TimeLimitedMap<K, V>(
    private val backMap: MutableMap<K, V>,
    private val timeSupplier: () -> Long,
    private val timeout: Duration
) : MutableMap<K, V> by backMap {
    private val timestamps = LinkedList<Pair<Long, K>>()

    override fun put(key: K, value: V): V? {
        val oldVal = backMap[key]

        if (oldVal == null) {
            timestamps += timeSupplier() to key
        }
        backMap[key] = value
        purge()
        return oldVal
    }

    override fun putAll(from: Map<out K, V>) {
        from.entries.forEach { put(it.key, it.value) }
    }

    private fun purge() {
        val timeline = timeSupplier() - timeout.toMillis()
        val it = timestamps.iterator()
        while (it.hasNext()) {
            val (entryT, key) = it.next()
            if (entryT < timeline || key !in backMap) {
                it.remove()
                backMap -= key
            } else {
                break
            }
        }
    }
}