package io.libp2p.etc.util.netty

import io.libp2p.etc.types.addAfter
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelInitializer
import io.netty.util.internal.StringUtil

class NettyInit(val channel: Channel, thisHandler: ChannelHandler) {
    private var lastLocalHandler = thisHandler
    fun addLastLocal(handler: ChannelHandler) {
        channel.pipeline().addAfter(lastLocalHandler, generateName(channel, handler), handler)
        lastLocalHandler = handler
    }
}

fun nettyInitializer(initer: (NettyInit) -> Unit): ChannelInitializer<Channel> {
    return object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) {
            initer.invoke(NettyInit(ch, this))
        }
    }
}

private fun generateName(ch: Channel, handler: ChannelHandler): String {
    val className = StringUtil.simpleClassName(handler.javaClass)
    val names = ch.pipeline().names().toSet()
    return (0..Int.MAX_VALUE).asSequence().map { "$className#$it" }.find { it !in names } ?: "Unexpected"
}
