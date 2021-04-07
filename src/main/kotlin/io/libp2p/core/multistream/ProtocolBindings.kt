package io.libp2p.core.multistream

import java.util.concurrent.CopyOnWriteArrayList

class ProtocolBindings<TController>(
    initList: List<ProtocolBinding<TController>> = listOf()
) {

    private val bindings: MutableList<ProtocolBinding<TController>> =
        CopyOnWriteArrayList(initList)

    fun addProtocols(vararg protocol: ProtocolBinding<TController>) {
        bindings.addAll(protocol)
    }

    fun removeProtocols(vararg protocolId: ProtocolId) {
        val toRemove = bindings.filter { it.protocolDescriptor.matchesAny(listOf(*protocolId)) }
        bindings.removeAll(toRemove)
    }

    fun getValues(): List<ProtocolBinding<TController>> {
        return bindings
    }
}
