package io.libp2p.core

import io.netty.channel.ChannelHandler
import java.util.function.Consumer

abstract class StreamHandler(val channelInitializer: ChannelHandler): Consumer<Stream>

class StreamHandlerMock(channelInitializer: ChannelHandler) : StreamHandler(channelInitializer) {
    override fun accept(t: Stream) {}
}