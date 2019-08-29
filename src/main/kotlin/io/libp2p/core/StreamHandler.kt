package io.libp2p.core

import io.netty.channel.ChannelHandler

interface StreamHandler {

    fun handleStream(stream: Stream)

    companion object {

        fun create(channelInitializer: ChannelHandler) = object : StreamHandler {
            override fun handleStream(stream: Stream) {
                stream.nettyChannel.pipeline().addLast(channelInitializer)
            }
        }

        fun create(channelHandler: P2PAbstractHandler<*>) = object : StreamHandler {
            override fun handleStream(stream: Stream) {
                channelHandler.initChannel(stream)
            }
        }
    }
}
