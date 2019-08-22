package io.libp2p.core

import io.netty.channel.ChannelHandler
import java.util.function.Consumer

interface StreamHandler : Consumer<Stream> {

    companion object {

        fun create(channelInitializer: ChannelHandler) = object : StreamHandler {
            override fun accept(stream: Stream) {
                stream.ch.pipeline().addLast(channelInitializer)
            }
        }

        fun create(channelHandler: P2PAbstractHandler<*>) = object : StreamHandler {
            override fun accept(stream: Stream) {
                channelHandler.initChannel(stream)
            }
        }
    }
}
