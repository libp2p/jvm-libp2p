package io.libp2p.etc.util.netty

import io.netty.channel.Channel
import io.netty.channel.ChannelException
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerAdapter
import io.netty.channel.ChannelHandlerContext
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Handler which closes the channel on timeout unless removed.
 *
 * Installed by [io.libp2p.multistream.Negotiator] to bound protocol-negotiation
 * time (and by circuit-relay setup). On successful negotiation it is removed from
 * the pipeline, which cancels the scheduled timeout via [handlerRemoved].
 *
 * It also cancels the timeout when the channel closes, via a listener on the
 * channel's close future. This is essential for channels — in particular
 * multiplexed [io.libp2p.etc.util.netty.mux.MuxChannel] substreams — that close
 * *before* negotiation completes: such a channel is not removed by application
 * code, so removal (and thus [handlerRemoved]) depends on the pipeline being
 * destroyed during the channel's *deferred* deregister, which runs as a regular
 * task on the channel's event loop. If that event loop is backlogged (e.g. a
 * reconnect / negotiation-abort herd on a CPU-constrained host) the deferred
 * deregister never runs, so [handlerRemoved] never fires, so the scheduled timeout
 * task — which captures the whole (closed) pipeline — stays pinned in the
 * event-loop scheduled-task queue until the timeout elapses. Under sustained churn
 * these closed-but-pinned pipelines accumulate unbounded and exhaust the heap: a
 * heap dump shows tens of thousands of pending TotalTimeoutHandler tasks each
 * rooting a closed MuxChannel + Negotiator$ResponderHandler pipeline, even though
 * the number of concurrently live substreams stays bounded.
 *
 * The close future completes synchronously while the channel is being closed, so
 * the listener cancels deterministically regardless of event-loop backlog or
 * channel state (`channelInactive` is insufficient: it is not fired for a channel
 * that closes while still in the OPEN state, which is the common case here).
 */
class TotalTimeoutHandler(val timeout: Duration) : ChannelHandlerAdapter() {
    private var timeoutTask: ScheduledFuture<*>? = null
    private var closeListener: ChannelFutureListener? = null

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        timeoutTask = ctx.executor().schedule({ onTimeout(ctx) }, timeout.toMillis(), TimeUnit.MILLISECONDS)
        val listener = ChannelFutureListener { cancel(it.channel()) }
        closeListener = listener
        ctx.channel().closeFuture().addListener(listener)
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        cancel(ctx.channel())
    }

    private fun cancel(channel: Channel?) {
        timeoutTask?.cancel(false)
        timeoutTask = null
        val listener = closeListener
        if (listener != null) {
            closeListener = null
            channel?.closeFuture()?.removeListener(listener)
        }
    }

    private fun onTimeout(ctx: ChannelHandlerContext) {
        ctx.fireExceptionCaught(TotalTimeoutException())
        ctx.close()
        timeoutTask = null
    }
}

class TotalTimeoutException : ChannelException()
