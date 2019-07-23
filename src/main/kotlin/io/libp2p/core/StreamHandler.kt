package io.libp2p.core

import io.libp2p.core.multistream.Multistream
import io.netty.channel.ChannelHandler
import java.util.function.Consumer

interface StreamHandler : Consumer<Stream> {
    val channelInitializer: ChannelHandler

    companion object {
        fun create(channelInitializer: ChannelHandler) = object : StreamHandler {
            override val channelInitializer = channelInitializer
            override fun accept(t: Stream) {}
        }
        fun create(multistream: Multistream<*>) = create(multistream.initializer().first)
    }
}
