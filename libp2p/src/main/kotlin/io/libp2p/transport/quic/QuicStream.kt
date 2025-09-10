package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.handler.codec.quic.QuicStreamChannel
import java.util.concurrent.CompletableFuture

class QuicStream(
    val quicStreamChannel: QuicStreamChannel,
    connection: Connection,
    initiator: Boolean
) : StreamOverNetty(quicStreamChannel, connection, initiator) {

    init {
        pushHandler(QuicStreamReadCloseEventConverter())
    }

    override fun closeWrite(): CompletableFuture<Unit> {
        return quicStreamChannel.shutdownOutput().toVoidCompletableFuture()
    }
}
