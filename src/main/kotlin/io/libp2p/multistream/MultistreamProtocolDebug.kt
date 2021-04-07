package io.libp2p.multistream

import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolDebug
import io.libp2p.core.multistream.ProtocolBinding

val MultistreamProtocolV1Impl: MultistreamProtocolDebug = MultistreamProtocolDebugV1()

private class MultistreamProtocolDebugV1 : MultistreamProtocolDebug {

    override val version = "1.0.0"

    override fun <TController> createMultistream(bindings: List<ProtocolBinding<TController>>) =
        MultistreamImpl(bindings)

    override fun copyWithHandlers(
        preHandler: P2PChannelHandler<*>?,
        postHandler: P2PChannelHandler<*>?
    ): MultistreamProtocol = MultistreamProtocolHandledV1(preHandler, postHandler)
}

private class MultistreamProtocolHandledV1(
    private val preHandler: P2PChannelHandler<*>? = null,
    private val postHandler: P2PChannelHandler<*>? = null
) : MultistreamProtocol {

    override val version = "1.0.0"

    override fun <TController> createMultistream(bindings: List<ProtocolBinding<TController>>) =
        MultistreamImpl(bindings, preHandler, postHandler)
}
