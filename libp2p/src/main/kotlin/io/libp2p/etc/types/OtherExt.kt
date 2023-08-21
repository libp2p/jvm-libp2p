package io.libp2p.etc.types

import com.google.common.base.Throwables
import kotlin.reflect.KClass

fun Boolean.whenTrue(run: () -> Unit): Boolean {
    if (this) {
        run()
    }
    return this
}

class Deferrable {
    private val actions: MutableList<() -> Unit> = mutableListOf()

    fun defer(f: () -> Unit) {
        actions.add(f)
    }

    fun execute() {
        actions.reversed().forEach {
            try {
                it()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/**
 * Acts like Go defer
 */
fun <T> defer(f: (Deferrable) -> T): T {
    val deferrable = Deferrable()
    try {
        return f(deferrable)
    } finally {
        deferrable.execute()
    }
}

fun <T : Throwable> Throwable.hasCauseOfType(clazz: KClass<T>) =
    Throwables.getCausalChain(this)
        .filter(clazz::isInstance)
        .any()
