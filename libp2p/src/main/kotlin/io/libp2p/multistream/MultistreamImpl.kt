package io.libp2p.multistream

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class MultistreamImpl<TController>(
    initList: List<ProtocolBinding<TController>> = listOf(),
    val preHandler: P2PChannelHandler<*>? = null,
    val postHandler: P2PChannelHandler<*>? = null,
    val negotiationTimeLimit: Duration = DEFAULT_NEGOTIATION_TIME_LIMIT
) : Multistream<TController> {

    override val bindings: List<ProtocolBinding<TController>> = initList

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController> {
        return with(ch) {
            preHandler?.also {
                it.initChannel(ch)
            }
            pushHandler(
                if (ch.isInitiator) {
                    Negotiator.createRequesterInitializer(
                        negotiationTimeLimit,
                        *bindings.flatMap { it.protocolDescriptor.announceProtocols }.toTypedArray()
                    )
                } else {
                    Negotiator.createResponderInitializer(
                        negotiationTimeLimit,
                        bindings.map { it.protocolDescriptor.protocolMatcher }
                    )
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
