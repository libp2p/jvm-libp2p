package io.libp2p.core.util.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer

fun nettyInitializer(initer: (Channel) -> Unit): ChannelInitializer<Channel> {
    return object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) {
            initer.invoke(ch)
        }
    }
}