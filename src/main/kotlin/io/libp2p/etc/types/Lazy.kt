package io.libp2p.etc.types

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Lazy initializer for 'var'. If the var value is overwritten before the first access
// then the default var value is never computed
fun <T> lazyVar(defaultValueInit: () -> T) = LazyMutable(defaultValueInit)

// thanks to https://stackoverflow.com/a/47948047/9630725
class LazyMutable<T>(val initializer: () -> T) : ReadWriteProperty<Any?, T> {
    private object UNINITIALIZED_VALUE
    private var prop: Any? = UNINITIALIZED_VALUE

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return if (prop == UNINITIALIZED_VALUE) {
            synchronized(this) {
                return if (prop == UNINITIALIZED_VALUE) initializer().also { prop = it } else prop as T
            }
        } else prop as T
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            prop = value
        }
    }
}