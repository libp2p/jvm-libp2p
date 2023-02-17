package io.libp2p.etc.types

fun <TValue1, TValue2> mutableMultiBiMap(): MutableMultiBiMap<TValue1, TValue2> = HashSetMultiBiMap()

/**
 * Associates values of type [TFirst] to a set of values of type [TSecond]
 * and back: associates values of type [TSecond] to a set of values of type [TFirst]
 */
interface MultiBiMap<TFirst, TSecond> {

    fun getByFirst(first: TFirst): Set<TSecond>
    fun getBySecond(second: TSecond): Set<TFirst>

    fun valuesFirst(): Set<TFirst>
    fun valuesSecond(): Set<TSecond>

    fun asFirstToSecondMap(): Map<TFirst, Set<TSecond>> = valuesFirst().associateWith { getByFirst(it) }
    fun asSecondToFirstMap(): Map<TSecond, Set<TFirst>> = valuesSecond().associateWith { getBySecond(it) }
}

interface MutableMultiBiMap<TFirst, TSecond> : MultiBiMap<TFirst, TSecond> {

    fun add(first: TFirst, second: TSecond)

    fun remove(first: TFirst, second: TSecond)
    fun removeAllByFirst(first: TFirst)
    fun removeAllBySecond(second: TSecond)
}

internal class HashSetMultiBiMap<TFirst, TSecond> : MutableMultiBiMap<TFirst, TSecond> {
    private val firstToSecondMap = mutableMapOf<TFirst, MutableSet<TSecond>>()
    private val secondToFirstMap = mutableMapOf<TSecond, MutableSet<TFirst>>()

    override fun getByFirst(first: TFirst): Set<TSecond> = firstToSecondMap[first] ?: emptySet()
    override fun getBySecond(second: TSecond): Set<TFirst> = secondToFirstMap[second] ?: emptySet()
    override fun valuesFirst(): Set<TFirst> = firstToSecondMap.keys
    override fun valuesSecond(): Set<TSecond> = secondToFirstMap.keys
    override fun asFirstToSecondMap(): Map<TFirst, Set<TSecond>> = firstToSecondMap
    override fun asSecondToFirstMap(): Map<TSecond, Set<TFirst>> = secondToFirstMap

    override fun add(first: TFirst, second: TSecond) {
        firstToSecondMap.computeIfAbsent(first) { mutableSetOf() } += second
        secondToFirstMap.computeIfAbsent(second) { mutableSetOf() } += first
    }

    private fun removeFromFirstToSecondMap(first: TFirst, second: TSecond) {
        firstToSecondMap.compute(first) { _, curSecondValues ->
            if (curSecondValues != null) {
                curSecondValues -= second
                if (curSecondValues.isNotEmpty()) {
                    curSecondValues
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    private fun removeFromSecondToFirstMap(first: TFirst, second: TSecond) {
        secondToFirstMap.compute(second) { _, curFirstValues ->
            if (curFirstValues != null) {
                curFirstValues -= first
                if (curFirstValues.isNotEmpty()) {
                    curFirstValues
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    override fun remove(first: TFirst, second: TSecond) {
        removeFromFirstToSecondMap(first, second)
        removeFromSecondToFirstMap(first, second)
    }

    override fun removeAllByFirst(first: TFirst) {
        val removedSecondValues = firstToSecondMap.remove(first) ?: emptySet()
        removedSecondValues.forEach { removeFromSecondToFirstMap(first, it) }
    }

    override fun removeAllBySecond(second: TSecond) {
        val removedFirstValues = secondToFirstMap.remove(second) ?: emptySet()
        removedFirstValues.forEach { removeFromFirstToSecondMap(it, second) }
    }
}
