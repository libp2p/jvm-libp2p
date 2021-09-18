package io.libp2p.transport.ws

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler

internal class WebSocketServerHandshakeListener(
    private val connectionBuilder: ChannelHandler
) : ChannelInboundHandlerAdapter() {

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is WebSocketServerProtocolHandler.HandshakeComplete) {
            ctx.pipeline().addLast(WebFrameCodec())
            ctx.pipeline().addLast(connectionBuilder)
            ctx.pipeline().remove(this)
            ctx.fireChannelActive()
        }
        super.userEventTriggered(ctx, evt)
    }
} // WebSocketServerHandshakeListener
