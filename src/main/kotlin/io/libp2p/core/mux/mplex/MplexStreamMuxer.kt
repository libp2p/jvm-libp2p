package io.libp2p.core.mux.mplex

import io.libp2p.core.events.MuxSessionFailed
import io.libp2p.core.events.MuxSessionInitialized
import io.libp2p.core.mplex.MplexFrameCodec
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolBindingInitializer
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.mux.MuxHandler
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.CompletableFuture

class MplexStreamMuxer : StreamMuxer {
    override val announce = "/mplex/6.7.0"
    override val matcher: ProtocolMatcher =
        ProtocolMatcher(Mode.STRICT, announce)
    var intermediateFrameHandler: ChannelHandler? = null

    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<StreamMuxer.Session> {
        val muxSessionFuture = CompletableFuture<StreamMuxer.Session>()
        val nettyInitializer = nettyInitializer {ch ->
            ch.pipeline().addLast(MplexFrameCodec())
            intermediateFrameHandler?.also { ch.pipeline().addLast(it) }
            ch.pipeline().addLast("MuxerSessionTracker", object : ChannelInboundHandlerAdapter() {
                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    when (evt) {
                        is MuxSessionInitialized -> {
                            muxSessionFuture.complete(evt.session)
                            ctx.pipeline().remove(this)
                        }
                        is MuxSessionFailed -> {
                            muxSessionFuture.completeExceptionally(evt.exception)
                            ctx.pipeline().remove(this)
                        }
                        else -> super.userEventTriggered(ctx, evt)
                    }
                }
            })
            ch.pipeline().addBefore("MuxerSessionTracker", "MuxHandler", MuxHandler())
        }
        return ProtocolBindingInitializer(nettyInitializer, muxSessionFuture)
    }
}