package io.libp2p.transport.ws

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.util.CharsetUtil
import java.net.URI

internal class WebSocketClientHandler(
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

    override fun channelInactive(ctx: ChannelHandlerContext) {
        println("WebSocket Client disconnected!")
    }

    public override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
        val ch = ctx.channel()
        if (!handshaker.isHandshakeComplete) {
            try {
                handshaker.finishHandshake(ch, msg as FullHttpResponse)
                println("WebSocket Client connected!")
                ctx.pipeline().remove(this)
                ctx.pipeline().addLast(connectionHandler)
            } catch (e: WebSocketHandshakeException) {
                println("WebSocket Client failed to connect")
            }
            return
        }

        if (msg is FullHttpResponse) {
            throw IllegalStateException("Unexpected FullHttpResponse (getStatus=" + msg.status() + ", content=" + msg.content().toString(CharsetUtil.UTF_8) + ')'.toString())
        }

        /*
        val frame = msg as WebSocketFrame
        if (frame is TextWebSocketFrame) {
            println("WebSocket Client received message: " + frame.text())
        } else if (frame is CloseWebSocketFrame) {
            println("WebSocket Client received closing")
            ch.close()
        }
        */
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        ctx.close()
    }
}
