package io.libp2p.core.multistream

import io.libp2p.core.P2PChannelHandler
import io.libp2p.multistream.MultistreamProtocolV1Impl

val MultistreamProtocolV1: MultistreamProtocolDebug = MultistreamProtocolV1Impl

interface MultistreamProtocol {

    val version: String

    /**
     * Creates [Multistream] implementation with a list of protocol bindings
     */
    fun <TController> createMultistream(bindings: List<ProtocolBinding<TController>>): Multistream<TController>
}

interface MultistreamProtocolDebug : MultistreamProtocol {

    fun copyWithHandlers(
        preHandler: P2PChannelHandler<*>? = null,
        postHandler: P2PChannelHandler<*>? = null
    ): MultistreamProtocol
}
