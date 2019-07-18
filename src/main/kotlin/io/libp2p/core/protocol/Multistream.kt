package io.libp2p.core.protocol

import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import java.util.concurrent.CompletableFuture

interface Multistream<TController> {

    val bindings: List<ProtocolBinding<TController>>

    fun initializer(): Pair<ChannelHandler, CompletableFuture<TController>>

    companion object {
        fun <TController> create(bindings: List<ProtocolBinding<TController>>, initiator: Boolean): Multistream<TController>
                = MultistreamImpl(bindings, initiator)
    }
}

class MultistreamImpl<TController>(override val bindings: List<ProtocolBinding<TController>>, val initiator: Boolean) :
    Multistream<TController> {

    override fun initializer(): Pair<ChannelHandler, CompletableFuture<TController>> {
        val fut = CompletableFuture<TController>()
        val handler = object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                ch.pipeline().addLast(Negotiator.createInitializer(initiator, *bindings.map { it.announce }.toTypedArray()))
                ch.pipeline().addLast(ProtocolSelect(bindings))
            }
        }

        return handler to fut
    }

}
