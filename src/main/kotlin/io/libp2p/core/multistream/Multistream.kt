package io.libp2p.core.multistream

import io.libp2p.core.types.forward
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

interface Multistream<TController> {

    val bindings: List<ProtocolBinding<TController>>

    fun initializer(): Pair<ChannelHandler, CompletableFuture<TController>>

    companion object {
        fun <TController> create(
            bindings: List<ProtocolBinding<TController>>
        ): Multistream<TController> = MultistreamImpl(bindings)
    }
}

class MultistreamImpl<TController>(override val bindings: List<ProtocolBinding<TController>>) :
    Multistream<TController> {

    override fun initializer(): Pair<ChannelHandler, CompletableFuture<TController>> {
        val fut = CompletableFuture<TController>()
        val handler = nettyInitializer {
            it.pipeline().addLast(
                Negotiator.createInitializer(
                    *bindings.map { it.announce }.toTypedArray()
                )
            )
            val protocolSelect = ProtocolSelect(bindings)
            protocolSelect.selectedFuture.forward(fut)
            it.pipeline().addLast(protocolSelect)
        }

        return handler to fut
    }
}
