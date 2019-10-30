package io.libp2p.mux.mplex

import io.libp2p.core.P2PChannel
import io.libp2p.core.mplex.MplexFrameCodec
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import io.libp2p.mux.MuxHandler
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

class MplexStreamMuxer : StreamMuxer, StreamMuxerDebug {
    override val announce = "/mplex/6.7.0"
    override val matcher: ProtocolMatcher = ProtocolMatcher(Mode.STRICT, announce)
    override var muxFramesDebugHandler: ChannelHandler? = null

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
        val muxSessionReady = CompletableFuture<StreamMuxer.Session>()

        ch.pushHandler(MplexFrameCodec())
        muxFramesDebugHandler?.also { ch.pushHandler(it) }
        ch.pushHandler(MuxHandler(muxSessionReady))

        return muxSessionReady
    }
}