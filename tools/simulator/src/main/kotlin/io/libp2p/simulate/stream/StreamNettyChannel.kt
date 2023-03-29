package io.libp2p.simulate.stream

import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.CompositeMessageDelayer
import io.libp2p.simulate.util.MsgSizeEstimator
import io.netty.channel.*
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.internal.ObjectUtil
import java.util.concurrent.ScheduledExecutorService

class StreamNettyChannel(
    id: String,
    override val stream: StreamSimStream,
    override val isStreamInitiator: Boolean,
    val inboundBandwidth: BandwidthDelayer,
    val outboundBandwidth: BandwidthDelayer,
    val executor: ScheduledExecutorService,
    val currentTime: CurrentTimeSupplier,
    val msgSizeEstimator: MsgSizeEstimator,
    vararg handlers: ChannelHandler?
) :
    SimChannel,
    EmbeddedChannel(
        SimChannelId(id),
        *handlers
    ) {

    override val msgVisitors: MutableList<SimChannelMessageVisitor> = mutableListOf()

    var link: StreamNettyChannel? = null

    private var msgDelayer: CompositeMessageDelayer by lazyVar {
        createMessageDelayer(outboundBandwidth, MessageDelayer.NO_DELAYER, inboundBandwidth)
//            .sequential(executor)
    }

    fun setLatency(latency: MessageDelayer) {
        msgDelayer = createMessageDelayer(outboundBandwidth, latency, inboundBandwidth)
    }

    private fun createMessageDelayer(
        outboundBandwidthDelayer: BandwidthDelayer,
        connectionLatencyDelayer: MessageDelayer,
        inboundBandwidthDelayer: BandwidthDelayer,
    ): CompositeMessageDelayer {
        return CompositeMessageDelayer(
            outboundBandwidthDelayer,
            connectionLatencyDelayer,
            inboundBandwidthDelayer,
            executor,
            currentTime
        )
    }

    @Synchronized
    fun connect(other: StreamNettyChannel) {
        while (outboundMessages().isNotEmpty()) {
            send(other, outboundMessages().poll())
        }
        link = other
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

        delay.thenApply { delayData ->
            other.executor.execute {
                msgVisitors.forEach { it.onInbound(msg, delayData) }
            }
            other.writeInbound(msg)
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
