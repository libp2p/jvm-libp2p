package io.libp2p.core.mux

import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.Stream
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.ProtocolBinding
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

data class StreamPromise<T>(val stream: CompletableFuture<Stream>, val controler: CompletableFuture<T>)

interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {

    override fun initializer(selectedProtocol: String): P2PAbstractHandler<Session>

    interface Session {
        var inboundStreamHandler: StreamHandler?
        fun <T> createStream(streamHandler: P2PAbstractHandler<T>): StreamPromise<T>
    }
}

interface StreamMuxerDebug {
    var muxFramesDebugHandler: ChannelHandler?
}