package io.libp2p.core.multistream

import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import io.libp2p.multistream.MultistreamImpl
import java.util.concurrent.CompletableFuture

/**
 * Represents 'multistream' concept: https://github.com/multiformats/multistream-select
 *
 *
 */
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
