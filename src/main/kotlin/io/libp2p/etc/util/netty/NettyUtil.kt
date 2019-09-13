package io.libp2p.etc.util.netty

import io.libp2p.etc.types.fromHex
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer

fun nettyInitializer(initer: (Channel) -> Unit): ChannelInitializer<Channel> {
    return object : ChannelInitializer<Channel>() {
        override fun initChannel(ch: Channel) {
            initer.invoke(ch)
        }
    }
}

private val regex = Regex("\\|[0-9a-fA-F]{8}\\| ")
fun String.fromLogHandler() = lines()
        .filter { it.contains(regex) }
        .map { it.substring(11, 59).replace(" ", "") }
        .flatMap { it.fromHex().asList() }
        .toByteArray()
