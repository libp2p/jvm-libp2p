package io.libp2p.simulate.stream

import io.libp2p.etc.types.lazyVar
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.ChannelMessageDelayer
import io.libp2p.simulate.delay.SequentialDelayer.Companion.sequential
import io.libp2p.simulate.util.GeneralSizeEstimator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelId
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.EventLoop
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.internal.ObjectUtil
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class StreamNettyChannel(
    id: String,
    override val stream: StreamSimStream,
    override val isStreamInitiator: Boolean,
    val inboundBandwidth: BandwidthDelayer,
    val outboundBandwidth: BandwidthDelayer,
    vararg handlers: ChannelHandler?
) :
    SimChannel, EmbeddedChannel(
        SimChannelId(id),
        *handlers
    ) {

    override val msgVisitors: MutableList<SimChannelMessageVisitor> = mutableListOf()

    var link: StreamNettyChannel? = null
    var executor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var currentTime: () -> Long = System::currentTimeMillis
    var msgSizeEstimator = GeneralSizeEstimator
    private var msgDelayer: MessageDelayer by lazyVar {
        createMessageDelayer(MessageDelayer.NO_DELAYER)
            .sequential(executor)
    }

    fun setLatency(latency: MessageDelayer) {
        msgDelayer = createMessageDelayer(latency)
    }

    private fun createMessageDelayer(
        connectionLatencyDelayer: MessageDelayer,
    ): MessageDelayer = ChannelMessageDelayer(executor, outboundBandwidth, connectionLatencyDelayer, inboundBandwidth)

    @Synchronized
    fun connect(other: StreamNettyChannel) {
        while (outboundMessages().isNotEmpty()) {
            send(other, outboundMessages().poll())
        }
        link = other
    }

    override fun writeInbound(vararg msgs: Any): Boolean {
        msgVisitors.forEach { visitor ->
            msgs.forEach { msg ->
                visitor.onInbound(msg)
            }
        }

        return super.writeInbound(*msgs)
    }

    @Synchronized
    override fun handleOutboundMessage(msg: Any) {
        if (link != null) {
            send(link!!, msg)
        } else {
            super.handleOutboundMessage(msg)
        }
    }

    private fun send(other: StreamNettyChannel, msg: Any) {
        msgVisitors.forEach { it.onOutbound(msg) }

        val size = msgSizeEstimator(msg)
        val delay = msgDelayer.delay(size)

        val sendNow: () -> Unit = {
            other.writeInbound(msg)
        }

        delay.thenApply {
            other.executor.execute(sendNow)
        }

//        // this prevents message reordering
//        val curT = currentTime()
//        val timeToSend = curT + delay
//        if (timeToSend < lastTimeToSend) {
//            delay = lastTimeToSend - curT
//        }
//        lastTimeToSend = curT + delay
//        if (delay > 0) {
//            other.executor.schedule(sendNow, delay, TimeUnit.MILLISECONDS)
//        } else {
//            other.executor.execute(sendNow)
//        }
    }

    private open class DelegatingEventLoop(val delegate: EventLoop) : EventLoop by delegate

    override fun eventLoop(): EventLoop {
        return object : DelegatingEventLoop(super.eventLoop()) {
            override fun execute(command: Runnable) {
                super.execute(command)
                runPendingTasks()
            }

            override fun register(channel: Channel): ChannelFuture {
                return register(DefaultChannelPromise(channel, this))
            }

            override fun register(promise: ChannelPromise): ChannelFuture {
                ObjectUtil.checkNotNull(promise, "promise")
                promise.channel().unsafe().register(this, promise)
                return promise
            }

            @Deprecated("Deprecated in Java")
            override fun register(channel: Channel, promise: ChannelPromise): ChannelFuture {
                channel.unsafe().register(this, promise)
                return promise
            }
        }
    }
}

private class SimChannelId(val id: String) : ChannelId {
    override fun compareTo(other: ChannelId) = asLongText().compareTo(other.asLongText())
    override fun asShortText() = id
    override fun asLongText() = id
}
