package io.libp2p.transport.ws

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler

internal class WebSocketClientInitializer(
    private val connectionBuilder: ChannelHandler,
    private val url: String
) : ChannelInitializer<SocketChannel>() {

    public override fun initChannel(ch: SocketChannel) {
        val pipeline = ch.pipeline()

        pipeline.addLast(HttpClientCodec())
        pipeline.addLast(HttpObjectAggregator(65536))
        pipeline.addLast(WebSocketClientCompressionHandler.INSTANCE)
        pipeline.addLast(
            WebSocketClientHandshake(
                connectionBuilder,
                url
            )
        )
    } // initChannel
} // WebSocketServerInitializer
