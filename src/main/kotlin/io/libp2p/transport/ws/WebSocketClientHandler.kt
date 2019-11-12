package io.libp2p.transport.ws

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketHandshakeException
import io.netty.util.CharsetUtil

class WebSocketClientHandler(
    private val handshaker: WebSocketClientHandshaker
) : SimpleChannelInboundHandler<Any>() {
    private lateinit var handshakeFuture: ChannelPromise

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        handshakeFuture = ctx.newPromise()
    }

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
                handshakeFuture.setSuccess()
            } catch (e: WebSocketHandshakeException) {
                println("WebSocket Client failed to connect")
                handshakeFuture.setFailure(e)
            }
            return
        }

        if (msg is FullHttpResponse) {
            throw IllegalStateException("Unexpected FullHttpResponse (getStatus=" + msg.status() + ", content=" + msg.content().toString(CharsetUtil.UTF_8) + ')'.toString())
        }

        val frame = msg as WebSocketFrame
        if (frame is TextWebSocketFrame) {
            println("WebSocket Client received message: " + frame.text())
        } else if (frame is CloseWebSocketFrame) {
            println("WebSocket Client received closing")
            ch.close()
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        cause.printStackTrace()
        if (!handshakeFuture.isDone) {
            handshakeFuture.setFailure(cause)
        }
        ctx.close()
    }
}
