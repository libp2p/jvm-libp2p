package io.libp2p.transport.quic

import io.libp2p.etc.util.netty.mux.RemoteWriteClosed
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

/**
 * Closes a QUIC stream channel once both sides have finished writing, the same rule the
 * muxer-based transports apply (see `MuxChannel.closeIfBothDisconnected`).
 *
 * Netty closes a bidirectional stream channel on its own only when our FIN was actually sent and
 * the remote FIN received. Our FIN is never sent when the peer has sent `STOP_SENDING`: quiche then
 * resets our send side instead (RFC 9000, section 3.5), refuses the FIN, and Netty keeps the channel
 * open whatever happens afterwards, even once the remote FIN has arrived. Such a stream would stay
 * open until the connection dies. Tracking "both sides finished" here, independently of whether the
 * FIN went out, makes the stream end exactly when a muxed stream would.
 *
 * The remote side is finished when [RemoteWriteClosed] is fired (converted from Netty's input
 * shutdown by [QuicStreamReadCloseEventConverter], which must sit before this handler); the local
 * side when [QuicStream.closeWrite] completes, whether or not the FIN could be sent.
 */
class QuicStreamBothSidesClosedHandler : ChannelInboundHandlerAdapter() {

    @Volatile
    private var ctx: ChannelHandlerContext? = null

    @Volatile
    var remoteWriteClosed = false
        private set

    @Volatile
    var localWriteClosed = false
        private set

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        this.ctx = ctx
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt === RemoteWriteClosed) {
            remoteWriteClosed = true
            closeIfBothSidesClosed()
        }
        ctx.fireUserEventTriggered(evt)
    }

    /** To be called once the local write side is finished, whether or not a FIN could be sent. */
    fun onLocalWriteClosed() {
        localWriteClosed = true
        closeIfBothSidesClosed()
    }

    private fun closeIfBothSidesClosed() {
        // Both flags are volatile and each caller sets its own flag before reading the other, so
        // when the two sides finish concurrently at least one of them sees both set. close() is
        // idempotent and thread-safe, so a duplicate close is harmless.
        if (remoteWriteClosed && localWriteClosed) {
            ctx?.close()
        }
    }
}
