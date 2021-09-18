package io.libp2p.mux.mplex

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.P2PChannel
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import java.util.concurrent.CompletableFuture

class MplexStreamMuxer(
    val inboundStreamHandler: StreamHandler<*>,
    private val multistreamProtocol: MultistreamProtocol
) : StreamMuxer, StreamMuxerDebug {

    override val protocolDescriptor = ProtocolDescriptor("/mplex/6.7.0")
    override var muxFramesDebugHandler: ChannelVisitor<Connection>? = null

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
        val muxSessionReady = CompletableFuture<StreamMuxer.Session>()

        ch.pushHandler(MplexFrameCodec())
        muxFramesDebugHandler?.also { it.visit(ch as Connection) }
        ch.pushHandler(MplexHandler(multistreamProtocol, muxSessionReady, inboundStreamHandler))

        return muxSessionReady
    }
}
