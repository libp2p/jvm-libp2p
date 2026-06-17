package io.libp2p.transport.quic

import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.channel.socket.ChannelOutputShutdownException
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
        when (cause) {
            is QuicStreamResetException -> {
                // The remote peer reset the stream. The muxer-based transports close the
                // stream quietly in this case (see AbstractMuxHandler.onRemoteClose) — match
                // that instead of surfacing the exception. Netty also leaves the stream
                // channel open after a reset, so close it here to not leak it.
                ctx.close()
            }
            is ChannelOutputShutdownException -> {
                // The remote peer sent STOP_SENDING: it no longer wants to read from this stream,
                // so Netty fails our queued writes with this exception. This is a benign half-close
                // — only our write side is affected; the read side may still deliver data — so we
                // swallow it rather than surfacing a spurious error to application handlers, and we
                // do NOT close the channel (matching Netty's own "should not close the channel"
                // handling of STOP_SENDING).
            }
            else -> ctx.fireExceptionCaught(cause)
        }
    }
}
