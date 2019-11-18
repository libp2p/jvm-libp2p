package io.libp2p.transport.ws

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import java.net.URI

internal class WebSocketClientHandshake(
    private val connectionHandler: ChannelHandler,
    val url: String
) : SimpleChannelInboundHandler<Any>() {

    private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
        URI(url),
        WebSocketVersion.V13,
        null,
        true,
        DefaultHttpHeaders()
    )

    override fun channelActive(ctx: ChannelHandlerContext) {
        handshaker.handshake(ctx.channel())
    }

    public override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        val ch = ctx.channel()
        if (!handshaker.isHandshakeComplete) {
            handshaker.finishHandshake(ch, msg as FullHttpResponse)
            ctx.pipeline().addLast(WebFrameCodec())
            ctx.pipeline().addLast(connectionHandler)
            ctx.pipeline().remove(this)
            ctx.fireChannelActive()
            return
        }

        throw IllegalStateException("Unexpected message in WebSocket Client")
    }
}
