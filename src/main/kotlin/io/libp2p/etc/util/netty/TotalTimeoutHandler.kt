package io.libp2p.etc.util.netty

import io.netty.channel.ChannelException
import io.netty.channel.ChannelHandlerAdapter
import io.netty.channel.ChannelHandlerContext
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Handler which closes connection on timeout unless removed
 */
class TotalTimeoutHandler(val timeout: Duration) : ChannelHandlerAdapter() {
    private var timeoutTask: ScheduledFuture<*>? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        timeoutTask = ctx.executor().schedule({ onTimeout(ctx) }, timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        cancel()
    }

    private fun cancel() {
        timeoutTask?.cancel(false)
    }

    private fun onTimeout(ctx: ChannelHandlerContext) {
        ctx.fireExceptionCaught(TotalTimeoutException())
        ctx.close()
        timeoutTask = null
    }
}

class TotalTimeoutException : ChannelException()
