package io.libp2p.core.util

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelPipeline

fun ChannelPipeline.replace(oldHandler: ChannelHandler, newHandlers: List<Pair<String, ChannelHandler>>) {
    replace(oldHandler, newHandlers[0].first, newHandlers[0].second)
    for (i in 1 until newHandlers.size) {
        addAfter(newHandlers[i - 1].first, newHandlers[i].first, newHandlers[i].second)
    }
}