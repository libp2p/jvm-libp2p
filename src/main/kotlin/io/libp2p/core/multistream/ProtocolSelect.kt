package io.libp2p.core.multistream

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.events.ProtocolNegotiationFailed
import io.libp2p.core.events.ProtocolNegotiationSucceeded
import io.libp2p.core.getP2PChannel
import io.libp2p.core.types.forward
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture

/**
 * Created by Anton Nashatyrev on 20.06.2019.
 */
class ProtocolSelect<TController>(val protocols: List<ProtocolBinding<TController>> = mutableListOf()) :
    ChannelInboundHandlerAdapter() {

    val selectedFuture = CompletableFuture<TController>()

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        when (evt) {
            is ProtocolNegotiationSucceeded -> {
                val protocolBinding = protocols.find { it.matcher.matches(evt.proto) }
                    ?: throw Libp2pException("Protocol negotiation failed: not supported protocol ${evt.proto}")
                val bindingInitializer = protocolBinding.initializer(evt.proto)
                ctx.pipeline().replace(this, "ProtocolBindingInitializer", nettyInitializer {
                    bindingInitializer.initChannel(it.getP2PChannel()).forward(selectedFuture)
                })
            }
            is ProtocolNegotiationFailed -> throw Libp2pException("ProtocolNegotiationFailed: $evt")
        }
        super.userEventTriggered(ctx, evt)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        ctx.close()
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        selectedFuture.completeExceptionally(ConnectionClosedException("Channel closed ${ctx.channel()}"))
    }
}