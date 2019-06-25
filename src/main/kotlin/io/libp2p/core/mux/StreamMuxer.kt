package io.libp2p.core.mux

import io.libp2p.core.Stream
import io.libp2p.core.protocol.ProtocolBinding
import java.util.concurrent.CompletableFuture

interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {
    interface Session {
        fun createStream(): CompletableFuture<Stream>
        fun close()
    }
}