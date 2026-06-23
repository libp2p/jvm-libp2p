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

    companion object {
        // Netty's quiche codec (QuicheQuicStreamChannel) raises ChannelOutputShutdownException with
        // exactly this message when the remote sent STOP_SENDING. The only other message it uses is
        // "Fin was sent already" for a local write-after-FIN, which must NOT be swallowed.
        private const val STOP_SENDING_MESSAGE = "STOP_SENDING frame received"
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
            ctx.fireUserEventTriggered(RemoteWriteClosed)
        } else {
            super.userEventTriggered(ctx, evt)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        when {
            cause is QuicStreamResetException -> {
                // The remote peer reset the stream. The muxer-based transports close the
                // stream quietly in this case (see AbstractMuxHandler.onRemoteClose) — match
                // that instead of surfacing the exception. Netty also leaves the stream
                // channel open after a reset, so close it here to not leak it.
                ctx.close()
            }
            cause is ChannelOutputShutdownException && cause.message == STOP_SENDING_MESSAGE -> {
                // The remote peer sent STOP_SENDING: it no longer wants to read from this stream,
                // so Netty fails our queued writes with this exception. This is a benign half-close
                // — only our write side is affected; the read side may still deliver data — so we
                // swallow it rather than surfacing a spurious error to application handlers, and we
                // do NOT close the channel (matching Netty's own "should not close the channel"
                // handling of STOP_SENDING). Other ChannelOutputShutdownException causes (e.g. a
                // local write after the FIN was sent) fall through to be propagated below: for
                // writeAndFlush with a void promise this pipeline exception is the only signal that
                // bytes were not accepted, so swallowing it would silently drop data.
            }
            else -> ctx.fireExceptionCaught(cause)
        }
    }
}
