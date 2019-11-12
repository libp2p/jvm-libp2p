package io.libp2p.transport.ws

import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import java.net.URI

internal class WebSocketClientInitializer(
    private val url: String
) : ChannelInitializer<SocketChannel>() {

    public override fun initChannel(ch: SocketChannel) {
        val pipeline = ch.pipeline()

        pipeline.addLast(HttpClientCodec())
        pipeline.addLast(HttpObjectAggregator(65536))
        pipeline.addLast(WebSocketClientCompressionHandler.INSTANCE)
        pipeline.addLast(WebSocketClientHandler(
            WebSocketClientHandshakerFactory.newHandshaker(
                URI(url),
                WebSocketVersion.V13,
                null,
                true,
                DefaultHttpHeaders()
            )
        ))
    } // initChannel
} // WebSocketServerInitializer