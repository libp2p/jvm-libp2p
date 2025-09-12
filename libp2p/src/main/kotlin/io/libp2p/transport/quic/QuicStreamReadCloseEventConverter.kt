package io.libp2p.transport.quic

import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ChannelInputShutdownReadComplete

/**
 * Convert QUIC library specific event on remote stream close to Libp2p specific event
 */
class QuicStreamReadCloseEventConverter : ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
            ctx.fireUserEventTriggered(RemoteWriteClosed)
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }
}
