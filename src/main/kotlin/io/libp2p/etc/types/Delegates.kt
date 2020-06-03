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

fun <T : Comparable<T>> cappedVar(value: T, lowerBound: T? = null, upperBound: T? = null) =
    CappedValueDelegate(value, lowerBound = lowerBound, upperBound = upperBound)

/**
 * Creates a Double delegate which may cap upper bound (set [upperBound] when the new value is greater)
 * and may drop value to [0.0] when the new value is less than [decayToZero]
 * When [decayToZero] is [null] then lower value is not modified
 * When [upperBound] is [null] then upper value is not modified
 */
fun cappedDouble(value: Double, decayToZero: Double? = null, upperBound: Double? = null) =
    CappedValueDelegate(value, decayToZero, 0.0, upperBound)

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
    var value: C,
    val lowerBound: C?,
    val lowerBoundVal: C? = lowerBound,
    val upperBound: C?,
    val upperBoundVal: C? = upperBound
) :
    ReadWriteProperty<Any?, C> {

    override fun getValue(thisRef: Any?, property: KProperty<*>): C {
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, newValue: C) {
        val v1 = if (upperBound != null && newValue > upperBound) upperBoundVal!! else newValue
        value = if (lowerBound != null && newValue < lowerBound) lowerBoundVal!! else v1
    }
}
