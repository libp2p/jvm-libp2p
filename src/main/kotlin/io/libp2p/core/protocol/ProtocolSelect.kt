package io.libp2p.core.protocol

import io.libp2p.core.Libp2pException
import io.libp2p.core.events.ProtocolNegotiationFailed
import io.libp2p.core.events.ProtocolNegotiationSucceeded
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

/**
 * Created by Anton Nashatyrev on 20.06.2019.
 */
class ProtocolSelect(val protocols: List<SecureChannel> = mutableListOf()): ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {

        if (evt is ProtocolNegotiationSucceeded) {
            val channel = protocols.find { it.matcher.matches(evt.proto) }
                ?: throw Libp2pException("Protocol negotiation failed: not supported protocol ${evt.proto}")
            ctx.pipeline().replace(this, "Selected-proto-handler", channel.initializer())

        } else if (evt is ProtocolNegotiationFailed) {
            throw Libp2pException("ProtocolNegotiationFailed: $evt")
        }
        super.userEventTriggered(ctx, evt)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        ctx.close()
    }
}