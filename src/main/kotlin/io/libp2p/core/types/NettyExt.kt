package io.libp2p.core.types

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import java.util.concurrent.CompletableFuture

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