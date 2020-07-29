package io.libp2p.transport.implementation

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

class ReadLimiter : ChannelInboundHandlerAdapter() {
    override fun channelWritabilityChanged(ctx: ChannelHandlerContext?) {
        if (ctx != null) {
            ctx.channel().config().isAutoRead = ctx.channel().isWritable
        }
        super.channelWritabilityChanged(ctx)
    }
}