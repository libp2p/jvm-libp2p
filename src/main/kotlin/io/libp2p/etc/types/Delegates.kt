package io.libp2p.etc.types

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Lazy initializer for 'var'. If the var value is overwritten before the first access
 * then the default var value is never computed
 */
fun <T> lazyVar(defaultValueInit: () -> T) = LazyMutable(defaultValueInit)

/**
 * Lazy initializer for 'var'. If the var value is overwritten before the first access
 * then the default var value is never computed
 * This variant prohibits setting a value after it was accessed
 */
fun <T> lazyVarInit(defaultValueInit: () -> T) = LazyMutable(defaultValueInit)

fun <T : Comparable<T>> cappedVar(value: T, lowerBound: T, upperBound: T) =
    CappedValueDelegate(value, lowerBound = { lowerBound }, upperBound = { upperBound })

/**
 * Creates a Double delegate which may drop value to [0.0] when the new value is less than [decayToZero]
 */
fun cappedDouble(value: Double, decayToZero: Double = Double.MIN_VALUE, updateListener: (Double) -> Unit = { }): CappedValueDelegate<Double> {
    return cappedDouble(value, decayToZero, { Double.MAX_VALUE }, updateListener)
}

/**
 * Creates a Double delegate which may cap upper bound (set [upperBound] when the new value is greater)
 * and may drop value to [0.0] when the new value is less than [decayToZero]
 */
fun cappedDouble(
    value: Double,
    decayToZero: Double = Double.MIN_VALUE,
    upperBound: () -> Double,
    updateListener: (Double) -> Unit = { }
) = CappedValueDelegate(value, { decayToZero }, { 0.0 }, upperBound, upperBound, updateListener)

// thanks to https://stackoverflow.com/a/47948047/9630725
class LazyMutable<T>(val initializer: () -> T, val rejectSetAfterGet: Boolean = false) : ReadWriteProperty<Any?, T> {
    private object UNINITIALIZED_VALUE
    private var prop: Any? = UNINITIALIZED_VALUE
    private var readAccessed = false

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return if (prop == UNINITIALIZED_VALUE) {
            synchronized(this) {
                readAccessed = true
                return if (prop == UNINITIALIZED_VALUE) initializer().also { prop = it } else prop as T
            }
        } else prop as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            if (rejectSetAfterGet && readAccessed) {
                throw IllegalStateException("This lazy property doesn't allow set() after get()")
            }
            prop = value
        }
    }
}

data class CappedValueDelegate<C : Comparable<C>>(
    private var value: C,
    private val lowerBound: () -> C,
    private val lowerBoundVal: () -> C = lowerBound,
    private val upperBound: () -> C,
    private val upperBoundVal: () -> C = upperBound,
    private val updateListener: (C) -> Unit = { }
) : ReadWriteProperty<Any?, C> {

    override fun getValue(thisRef: Any?, property: KProperty<*>): C {
        val oldValue = this.value
        val v1 = if (value > upperBound()) upperBoundVal() else value
        value = if (value < lowerBound()) lowerBoundVal() else v1
        if (oldValue != value) {
            updateListener(value)
        }
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: C) {
        val oldValue = this.value
        this.value = value
        if (oldValue != value) {
            updateListener(value)
        }
    }
}
