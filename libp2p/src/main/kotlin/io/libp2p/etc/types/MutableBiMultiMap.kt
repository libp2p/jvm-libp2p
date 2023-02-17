package io.libp2p.etc.types

import com.google.common.annotations.VisibleForTesting

/**
 * Creates new empty [MutableBiMultiMap]
 */
fun <K, V> mutableBiMultiMap(): MutableBiMultiMap<K, V> = MutableBiMultiMapImpl()

/**
 * Similar to `Map<Key, Value>` with bidirectional `Key <-> Value` mapping
 * Unlike keys values are not distinct. Querying keys by a value returns all existing keys for this value
 */
interface BiMultiMap<Key, Value> {

    /** Same as [Map.get] */
    fun getValue(key: Key): Value?

    /**
     * Returns all the keys corresponding to the [value]
     * @return empty collection if no such value exists
     */
    fun getKeys(value: Value): Collection<Key>

    /** Same as [Map.size] */
    fun size(): Int

    /** Same as [Map.isEmpty] */
    fun isEmpty() = size() == 0
}

/**
 * Similar to `MutableMap<Key, Value>` with bidirectional `Key <-> Value` mapping
 * Unlike keys values are not distinct. Querying keys by a value returns all existing keys for this value
 */
interface MutableBiMultiMap<Key, Value> : BiMultiMap<Key, Value> {

    /** Same as [MutableMap.put] */
    fun put(key: Key, value: Value)

    /** Same as [MutableMap.remove] */
    fun removeKey(key: Key)

    /**
     * Removes all the entries with specified [value]
     */
    fun removeAllByValue(value: Value)
}

operator fun <K, V> MutableBiMultiMap<K, V>.get(key: K) = getValue(key)
operator fun <K, V> MutableBiMultiMap<K, V>.set(key: K, value: V) = put(key, value)
operator fun <K, V> MutableBiMultiMap<K, V>.contains(key: K) = this[key] != null
operator fun <K, V> MutableBiMultiMap<K, V>.minusAssign(key: K) = removeKey(key)

internal class MutableBiMultiMapImpl<Key, Value> : MutableBiMultiMap<Key, Value> {
    @VisibleForTesting
    internal val keyToValue: MutableMap<Key, Value> = mutableMapOf()
    @VisibleForTesting
    internal val valueToKeys: MutableMap<Value, Set<Key>> = mutableMapOf()

    override fun put(key: Key, value: Value) {
        keyToValue.put(key, value)?.also { oldValue ->
            removeKeyForValue(key, oldValue)
        }

        valueToKeys.compute(value) { _, existingKeys ->
            existingKeys?.plus(key) ?: setOf(key)
        }
    }

    override fun getValue(key: Key): Value? = keyToValue[key]
    override fun getKeys(value: Value): Collection<Key> = valueToKeys[value] ?: emptyList()

    override fun removeKey(key: Key) {
        keyToValue.remove(key)?.also { existingValue ->
            removeKeyForValue(key, existingValue)
        }
    }

    private fun removeKeyForValue(key: Key, value: Value) {
        valueToKeys.compute(value) { _, existingKeys ->
            existingKeys?.minus(key)?.let { resultingKeys ->
                // return null if resulting key set is empty to remove valueToKeys entry
                if (resultingKeys.isEmpty()) null else resultingKeys
            }
        }
    }

    override fun removeAllByValue(value: Value) {
        valueToKeys.remove(value)?.forEach { keyToValue -= it }
    }
    override fun size() = keyToValue.size
}
