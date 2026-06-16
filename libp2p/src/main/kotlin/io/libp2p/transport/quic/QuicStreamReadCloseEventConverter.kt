package io.libp2p.transport.quic

import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.handler.codec.quic.QuicStreamResetException

/**
 * Convert QUIC library specific events on remote stream close to Libp2p specific behavior
 */
class QuicStreamReadCloseEventConverter : ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
            ctx.fireUserEventTriggered(RemoteWriteClosed)
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is QuicStreamResetException) {
            // The remote peer reset the stream. The muxer-based transports close the
            // stream quietly in this case (see AbstractMuxHandler.onRemoteClose) — match
            // that instead of surfacing the exception. Netty also leaves the stream
            // channel open after a reset, so close it here to not leak it.
            ctx.close()
        } else {
            ctx.fireExceptionCaught(cause)
        }
    }
}
