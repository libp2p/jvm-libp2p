package io.libp2p.etc.types

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedList
import java.util.function.Predicate

fun <C> Collection<C>.copy(): Collection<C> = this.toMutableList()

class LRUCollections {
    companion object {
        fun <C> createSet(maxSize: Int): MutableSet<C> = Collections.newSetFromMap(createMap(maxSize))

        fun <K, V> createMap(maxSize: Int): MutableMap<K, V> =
            object : LinkedHashMap<K, V>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                    return size > maxSize
                }
            }
    }
}

class LimitedList<C>(val maxSize: Int) : LinkedList<C>() {
    var onDropCallback: ((C) -> Unit)? = null

    override fun add(element: C): Boolean {
        val ret = super.add(element)
        while (size > maxSize) shrink()
        return ret
    }

    fun shrink() {
        onDropCallback?.invoke(removeFirst())
    }

    fun onDrop(callback: (C) -> Unit): LimitedList<C> {
        this.onDropCallback = callback
        return this
    }
}

// experimental
class MultiSet<K, V> : Iterable<Map.Entry<K, MutableList<V>>> {

    inner class MSList(val key: K) : ArrayList<V>() {
        private fun retain() {
            if (isEmpty()) {
                holder[key] = this
            }
        }

        private fun release() {
            if (isEmpty()) {
                holder.remove(key)
            }
        }

        override fun add(element: V): Boolean {
            retain()
            return super.add(element)
        }

        override fun add(index: Int, element: V) {
            retain()
            super.add(index, element)
        }

        override fun addAll(elements: Collection<V>): Boolean {
            retain()
            return super.addAll(elements)
        }

        override fun addAll(index: Int, elements: Collection<V>): Boolean {
            retain()
            return super.addAll(index, elements)
        }

        override fun removeAll(elements: Collection<V>): Boolean {
            val ret = super.removeAll(elements)
            release()
            return ret
        }

        override fun removeRange(fromIndex: Int, toIndex: Int) {
            super.removeRange(fromIndex, toIndex)
            release()
        }

        override fun removeAt(index: Int): V {
            val ret = super.removeAt(index)
            release()
            return ret
        }

        override fun remove(element: V): Boolean {
            val ret = super.remove(element)
            release()
            return ret
        }

        override fun removeIf(filter: Predicate<in V>): Boolean {
            val ret = super.removeIf(filter)
            release()
            return ret
        }
    }

    private val holder = mutableMapOf<K, MutableList<V>>()
    private val values = holder.values

    public operator fun get(key: K): MutableList<V> = holder.getOrPut(key, { MSList(key) })

    public fun removeAll(key: K) = holder.remove(key)

    override fun iterator(): Iterator<Map.Entry<K, MutableList<V>>> = holder.entries.iterator()
}

operator fun <C> List<C>.get(range: IntRange): List<C> {
    return subList(range.first, range.last + 1)
}