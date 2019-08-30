package io.libp2p.core.multistream

import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

interface Multistream<TController> : P2PAbstractHandler<TController> {

    val bindings: MutableList<ProtocolBinding<TController>>

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<TController>

    companion object {
        @JvmStatic
        fun <TController> create(): Multistream<TController> = MultistreamImpl()
        @JvmStatic
        fun <TController> create(
            vararg bindings: ProtocolBinding<TController>
        ): Multistream<TController> = MultistreamImpl(listOf(*bindings))
        @JvmStatic
        fun <TController> create(
            bindings: List<ProtocolBinding<TController>>
        ): Multistream<TController> = MultistreamImpl(bindings)
        @JvmStatic
        fun <TController> initiator(protocol: String, handler: P2PAbstractHandler<TController>): Multistream<TController> =
            create(ProtocolBinding.createSimple(protocol, handler))
    }
}

class MultistreamImpl<TController>(initList: List<ProtocolBinding<TController>> = listOf()) :
    Multistream<TController> {

    override val bindings: MutableList<ProtocolBinding<TController>> = CopyOnWriteArrayList(initList)

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<TController> {
        return with(ch.nettyChannel) {
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
