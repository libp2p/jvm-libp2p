package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.channel.socket.ChannelOutputShutdownException
import io.netty.handler.codec.quic.QuicStreamChannel
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class QuicStream(
    val quicStreamChannel: QuicStreamChannel,
    connection: Connection,
    initiator: Boolean
) : StreamOverNetty(quicStreamChannel, connection, initiator) {

    init {
        pushHandler(QuicStreamReadCloseEventConverter())
    }

    override fun closeWrite(): CompletableFuture<Unit> {
        // closeWrite must be idempotent and tolerate an already closed stream (e.g. when the
        // connection was closed first), matching the muxer-based transports where this is a no-op.
        // Netty fails shutdownOutput in both cases since closing a QUIC stream also sends the FIN.
        if (!quicStreamChannel.isOpen || quicStreamChannel.isOutputShutdown) {
            return CompletableFuture.completedFuture(Unit)
        }
        return quicStreamChannel.shutdownOutput().toVoidCompletableFuture()
            .exceptionally { t ->
                val cause = if (t is CompletionException) t.cause ?: t else t
                when (cause) {
                    // FIN already sent or stream already closed concurrently: write side is
                    // closed, which is all the caller asked for
                    is ChannelOutputShutdownException, is ClosedChannelException -> Unit
                    else -> throw t
                }
            }
    }
}
