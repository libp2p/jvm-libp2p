package io.libp2p.etc.util.netty

import io.libp2p.core.ProtocolViolationException
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

/**
 * Limits the total inbound traffic for the Channel
 */
class InboundTrafficLimitHandler(
    private var inboundBytesLimit: Long
) : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as ByteBuf
        inboundBytesLimit -= msg.readableBytes()
        if (inboundBytesLimit >= 0) {
            ctx.fireChannelRead(msg)
        } else {
            msg.release()
            ctx.fireExceptionCaught(ProtocolViolationException("Inbound traffic quota reached"))
            ctx.close()
        }
    }
}
