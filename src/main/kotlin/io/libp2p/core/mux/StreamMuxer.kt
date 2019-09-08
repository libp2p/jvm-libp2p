package io.libp2p.core.mux

import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.ProtocolBinding
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {

    override fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<out Session>

    interface Session {
        var inboundStreamHandler: StreamHandler<*>?
        fun <T> createStream(streamHandler: StreamHandler<T>): StreamPromise<T>
    }
}

interface StreamMuxerDebug {
    var muxFramesDebugHandler: ChannelHandler?
}