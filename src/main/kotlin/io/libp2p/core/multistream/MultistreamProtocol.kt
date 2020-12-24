package io.libp2p.core.multistream

import io.libp2p.multistream.MultistreamImpl

val MultistreamProtocol_v_1_0_0: MultistreamProtocol = object : MultistreamProtocol {

    override val version = "1.0.0"

    /**
     * Creates [Multistream] implementation with a list of protocol bindings
     */
    override fun <TController> create(
        bindings: List<ProtocolBinding<TController>>
    ): Multistream<TController> = MultistreamImpl(bindings)
}

interface MultistreamProtocol {

    val version: String

    /**
     * Creates [Multistream] implementation with a list of protocol bindings
     */
    fun <TController> create(bindings: List<ProtocolBinding<TController>>): Multistream<TController>
}