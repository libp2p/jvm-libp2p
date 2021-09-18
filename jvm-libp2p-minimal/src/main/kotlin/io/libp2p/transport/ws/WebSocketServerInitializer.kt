package io.libp2p.transport.ws

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler

internal class WebSocketServerInitializer(
    private val connectionBuilder: ChannelHandler
) : ChannelInitializer<SocketChannel>() {

    public override fun initChannel(ch: SocketChannel) {
        val pipeline = ch.pipeline()

        pipeline.addLast(HttpServerCodec())
        pipeline.addLast(HttpObjectAggregator(65536))
        pipeline.addLast(WebSocketServerCompressionHandler())
        pipeline.addLast(WebSocketServerProtocolHandler("/", null, true))
        pipeline.addLast(WebSocketServerHandshakeListener(connectionBuilder))
    } // initChannel
} // WebSocketServerInitializer
