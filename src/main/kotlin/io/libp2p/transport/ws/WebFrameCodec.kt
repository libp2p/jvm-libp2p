package io.libp2p.transport.ws

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageCodec
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame

class WebFrameCodec : MessageToMessageCodec<WebSocketFrame, ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        msg.retain()
        out.add(BinaryWebSocketFrame(msg))
    } // encode

    override fun decode(ctx: ChannelHandlerContext, msg: WebSocketFrame, out: MutableList<Any>) {
        msg as BinaryWebSocketFrame
        out.add(msg.retain().content())
    } // decode
} // WebFrameCodoc
