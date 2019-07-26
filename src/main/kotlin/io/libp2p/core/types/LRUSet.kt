package io.libp2p.core.types

import java.util.Collections
import java.util.LinkedHashMap

class LRUSet {
    companion object {
        fun <C> create(maxSize: Int): Set<C> {
            return Collections.newSetFromMap(object : LinkedHashMap<C, Boolean>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<C, Boolean>?): Boolean {
                    return size > maxSize
                }
            })
        }
    }
}