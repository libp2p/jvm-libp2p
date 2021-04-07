package io.libp2p.multistream

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture

class MultistreamImpl<TController>(
    override val bindings: List<ProtocolBinding<TController>>,
    val preHandler: P2PChannelHandler<*>? = null,
    val postHandler: P2PChannelHandler<*>? = null
) : Multistream<TController> {

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController> {
        return with(ch) {
            preHandler?.also {
                it.initChannel(ch)
            }
            pushHandler(
                if (ch.isInitiator) {
                    Negotiator.createRequesterInitializer(*bindings.flatMap { it.protocolDescriptor.announceProtocols }.toTypedArray())
                } else {
                    Negotiator.createResponderInitializer(bindings.map { it.protocolDescriptor.protocolMatcher })
                }
            )
            postHandler?.also {
                it.initChannel(ch)
            }
            val protocolSelect = ProtocolSelect(bindings)
            pushHandler(protocolSelect)
            protocolSelect.selectedFuture
        }
    }
}
