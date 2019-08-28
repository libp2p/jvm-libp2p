package io.libp2p.core.multistream

import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import java.util.concurrent.CompletableFuture

interface Multistream<TController> : P2PAbstractHandler<TController> {

    val bindings: List<ProtocolBinding<TController>>

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<TController>

    companion object {
        fun <TController> create(
            vararg bindings: ProtocolBinding<TController>
        ): Multistream<TController> = MultistreamImpl(listOf(*bindings))
        fun <TController> create(
            bindings: List<ProtocolBinding<TController>>
        ): Multistream<TController> = MultistreamImpl(bindings)
    }
}

class MultistreamImpl<TController>(override val bindings: List<ProtocolBinding<TController>>) :
    Multistream<TController> {

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<TController> {
        return with(ch.ch) {
            pipeline().addLast(
                if (ch.isInitiator) {
                    Negotiator.createRequesterInitializer(*bindings.map { it.announce }.toTypedArray())
                } else {
                    Negotiator.createResponderInitializer(bindings.map { it.matcher })
                }
            )
            val protocolSelect = ProtocolSelect(bindings)
            pipeline().addLast(protocolSelect)
            protocolSelect.selectedFuture
        }
    }
}
