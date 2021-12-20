package io.libp2p.multistream

import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolDebug
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.etc.types.seconds
import java.time.Duration

val DEFAULT_NEGOTIATION_TIME_LIMIT = 10.seconds

class MultistreamProtocolDebugV1(
    private val negotiationTimeLimit: Duration = DEFAULT_NEGOTIATION_TIME_LIMIT,
    private val preHandler: P2PChannelHandler<*>? = null,
    private val postHandler: P2PChannelHandler<*>? = null
) : MultistreamProtocolDebug {

    override val version = "1.0.0"

    override fun <TController> createMultistream(bindings: List<ProtocolBinding<TController>>) =
        MultistreamImpl(bindings, preHandler, postHandler, negotiationTimeLimit)

    override fun copyWithHandlers(
        preHandler: P2PChannelHandler<*>?,
        postHandler: P2PChannelHandler<*>?
    ): MultistreamProtocol = MultistreamProtocolDebugV1(negotiationTimeLimit, preHandler, postHandler)
}
