package io.libp2p.core.mux

import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolBindingInitializer
import java.util.concurrent.CompletableFuture

interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {

    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<Session>

    interface Session {
        var streamHandler: StreamHandler?
        fun createStream(streamHandler: StreamHandler): CompletableFuture<Stream>
        fun close() : Unit = TODO()
    }
}