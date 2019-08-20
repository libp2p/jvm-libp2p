package io.libp2p.core.protocol

import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolBindingInitializer
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.types.lazyVar
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.ReferenceCountUtil
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Ping: ProtocolBinding<Unit> {
    override val announce = "/ipfs/ping/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = announce)
    var scheduler  by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var period = Duration.ofSeconds(10)
    var pingSize = 32

    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<Unit> {
        TODO()
    }

    inner class PingResponderChannelHandler: ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            ReferenceCountUtil.retain(msg)
            ctx.writeAndFlush(msg)
        }
    }

    inner class PingInitiatorChannelHandler: ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            super.channelRead(ctx, msg)
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext) {
            super.channelUnregistered(ctx)
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            scheduler.scheduleAtFixedRate( { }, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
            super.exceptionCaught(ctx, cause)
        }
    }}

