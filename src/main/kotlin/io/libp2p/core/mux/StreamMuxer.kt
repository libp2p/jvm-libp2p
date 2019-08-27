package io.libp2p.core.mux

import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.ProtocolBinding
import java.util.concurrent.CompletableFuture

interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {

    override fun initializer(selectedProtocol: String): P2PAbstractHandler<Session>

    interface Session {
        var streamHandler: StreamHandler?
        fun <T> createStream(streamHandler: P2PAbstractHandler<T>): CompletableFuture<T>
        fun close(): Unit = TODO()
    }
}