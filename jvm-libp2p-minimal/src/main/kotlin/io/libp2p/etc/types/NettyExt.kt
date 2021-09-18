package io.libp2p.etc.types

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelPipeline
import java.util.concurrent.CompletableFuture

fun ChannelFuture.toVoidCompletableFuture(): CompletableFuture<Unit> = toCompletableFuture().thenApply { }

fun ChannelFuture.toCompletableFuture(): CompletableFuture<Channel> {
    val ret = CompletableFuture<Channel>()
    this.addListener {
        if (it.isSuccess) {
            ret.complete(this.channel())
        } else {
            ret.completeExceptionally(it.cause())
        }
    }
    return ret
}

fun ChannelPipeline.replace(oldHandler: ChannelHandler, newHandlers: List<Pair<String, ChannelHandler>>) {
    replace(oldHandler, newHandlers[0].first, newHandlers[0].second)
    for (i in 1 until newHandlers.size) {
        addAfter(newHandlers[i - 1].first, newHandlers[i].first, newHandlers[i].second)
    }
}

fun ChannelPipeline.getHandlerName(handler: ChannelHandler) = (
    toMap().entries.find { it.value === handler }?.key
        ?: throw IllegalArgumentException("Handler $handler not found in pipeline $this")
    )

fun ChannelPipeline.addAfter(handler: ChannelHandler, newHandlerName: String, newHandler: ChannelHandler) =
    addAfter(getHandlerName(handler), newHandlerName, newHandler)
