package io.libp2p.core.multistream

import java.util.concurrent.CopyOnWriteArrayList

class ProtocolBindings<TController>(
    initList: List<ProtocolBinding<TController>> = listOf()
) {

    private val bindings: MutableList<ProtocolBinding<TController>> =
        CopyOnWriteArrayList(initList)
    private val listeners: MutableList<ProtocolUpdateListener<TController>> = CopyOnWriteArrayList()

    fun addProtocols(vararg protocols: ProtocolBinding<TController>) {
        synchronized(bindings) {
            val toAdd = protocols.filter { !bindings.contains(it) }
            if (bindings.addAll(toAdd)) {
                listeners.forEach { it.onUpdate(toAdd, emptyList()) }
            }
        }
    }

    fun removeProtocols(vararg protocolId: ProtocolId) {
        synchronized(bindings) {
            val toRemove = bindings.filter { it.protocolDescriptor.matchesAny(listOf(*protocolId)) }
            if (bindings.removeAll(toRemove)) {
                listeners.forEach { it.onUpdate(emptyList(), toRemove) }
            }
        }
    }

    fun getValues(): List<ProtocolBinding<TController>> {
        return bindings
    }

    fun addListener(listener: ProtocolUpdateListener<TController>) {
        listeners.add(listener)
    }

    @FunctionalInterface
    interface ProtocolUpdateListener<T> {
        fun onUpdate(added: List<ProtocolBinding<T>>, removed: List<ProtocolBinding<T>>)
    }
}
