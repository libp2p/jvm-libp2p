package io.libp2p.mux.yamux

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.P2PChannel
import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.mux.StreamMuxerDebug
import java.util.concurrent.CompletableFuture

class YamuxStreamMuxer(
    val inboundStreamHandler: StreamHandler<*>,
    private val multistreamProtocol: MultistreamProtocol
) : StreamMuxer, StreamMuxerDebug {

    override val protocolDescriptor = ProtocolDescriptor("/yamux/1.0.0")
    override var muxFramesDebugHandler: ChannelVisitor<Connection>? = null

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out StreamMuxer.Session> {
        val muxSessionReady = CompletableFuture<StreamMuxer.Session>()

        val yamuxFrameCodec = YamuxFrameCodec()
        ch.pushHandler(yamuxFrameCodec)
        muxFramesDebugHandler?.also { it.visit(ch as Connection) }
        ch.pushHandler(
            YamuxHandler(
                multistreamProtocol,
                yamuxFrameCodec.maxFrameDataLength,
                muxSessionReady,
                inboundStreamHandler,
                ch.isInitiator
            )
        )

        return muxSessionReady
    }
}
