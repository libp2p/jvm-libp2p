package io.libp2p.core.mux

import io.libp2p.core.StreamHandler
import io.libp2p.core.protocol.ProtocolBinding
import java.util.concurrent.CompletableFuture

interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {
    interface Session {
        fun setInboundStreamHandler(steamHandler: StreamHandler)
        fun createStream(steamHandler: StreamHandler): CompletableFuture<Void>
        fun close()
    }
}