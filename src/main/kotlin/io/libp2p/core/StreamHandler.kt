package io.libp2p.core

import io.libp2p.core.multistream.Multistream
import io.netty.channel.ChannelHandler
import java.util.function.Consumer

interface StreamHandler : Consumer<Stream> {

    companion object {

        fun create(channelInitializer: ChannelHandler) = object : StreamHandler {
            override fun accept(stream: Stream) {
                stream.ch.pipeline().addLast(channelInitializer)
            }
        }
        fun create(multistream: Multistream<*>) = create(multistream.initializer().first)
    }
}
