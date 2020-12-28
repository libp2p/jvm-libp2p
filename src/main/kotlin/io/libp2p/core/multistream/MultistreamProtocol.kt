package io.libp2p.core.multistream

import io.libp2p.core.P2PChannelHandler
import io.libp2p.multistream.MultistreamImpl

val MultistreamProtocol_v_1_0_0: MultistreamProtocolDebug = MultistreamProtocolDebug_v_1_0_0()

interface MultistreamProtocol {

    val version: String

    /**
     * Creates [Multistream] implementation with a list of protocol bindings
     */
    fun <TController> create(bindings: List<ProtocolBinding<TController>>): Multistream<TController>
}

interface MultistreamProtocolDebug : MultistreamProtocol {

    fun copyWithHandlers(
        preHandler: P2PChannelHandler<*>? = null,
        postHandler: P2PChannelHandler<*>? = null
    ): MultistreamProtocol
}

class MultistreamProtocolDebug_v_1_0_0(
    private val preHandler: P2PChannelHandler<*>? = null,
    private val postHandler: P2PChannelHandler<*>? = null
) : MultistreamProtocolDebug {

    override val version = "1.0.0"

    override fun <TController> create(bindings: List<ProtocolBinding<TController>>) =
        MultistreamImpl(bindings, preHandler, postHandler)

    override fun copyWithHandlers(
        preHandler: P2PChannelHandler<*>?,
        postHandler: P2PChannelHandler<*>?
    ) = MultistreamProtocolDebug_v_1_0_0(preHandler, postHandler)
}