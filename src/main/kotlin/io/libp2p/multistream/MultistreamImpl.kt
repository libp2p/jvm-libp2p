package io.libp2p.multistream

import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

class MultistreamImpl<TController>(initList: List<ProtocolBinding<TController>> = listOf()) :
    Multistream<TController> {

    override val bindings: MutableList<ProtocolBinding<TController>> =
        CopyOnWriteArrayList(initList)

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<TController> {
        return with(ch) {
            pushHandler(
                if (ch.isInitiator) {
                    Negotiator.createRequesterInitializer(*bindings.map { it.announce }.toTypedArray())
                } else {
                    Negotiator.createResponderInitializer(bindings.map { it.matcher })
                }
            )
            val protocolSelect = ProtocolSelect(bindings)
            pushHandler(protocolSelect)
            protocolSelect.selectedFuture
        }
    }
}