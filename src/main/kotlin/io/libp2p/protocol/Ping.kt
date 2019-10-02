package io.libp2p.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.completedExceptionally
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import java.time.Duration
import java.util.Collections
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface PingController {
    fun ping(): CompletableFuture<Long>
}

class Ping : PingBinding(PingProtocol())

open class PingBinding(ping: PingProtocol) : StrictProtocolBinding<PingController>(ping) {
    override val announce = "/ipfs/ping/1.0.0"
}

class PingTimeoutException : Libp2pException()

class PingProtocol : P2PChannelHandler<PingController> {
    var scheduler by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var curTime: () -> Long = { System.currentTimeMillis() }
    var random = Random()
    var pingSize = 32
    var pingTimeout = Duration.ofSeconds(10)

    override fun initChannel(ch: P2PChannel): CompletableFuture<PingController> {
        return if (ch.isInitiator) {
            val handler = PingInitiatorChannelHandler()
            ch.pushHandler(handler)
            handler.activeFuture.thenApply { handler }
        } else {
            val handler = PingResponderChannelHandler()
            ch.pushHandler(handler)
            CompletableFuture.completedFuture(handler)
        }
    }

    inner class PingResponderChannelHandler : ChannelInboundHandlerAdapter(), PingController {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            ctx.writeAndFlush(msg)
        }

        override fun ping(): CompletableFuture<Long> {
            throw Libp2pException("This is ping responder only")
        }
    }

    inner class PingInitiatorChannelHandler : SimpleChannelInboundHandler<ByteBuf>(),
        PingController {
        val activeFuture = CompletableFuture<Unit>()
        val requests = Collections.synchronizedMap(mutableMapOf<String, Pair<Long, CompletableFuture<Long>>>())
        lateinit var ctx: ChannelHandlerContext
        var closed = false

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val dataS = msg.toByteArray().toHex()
            val (sentT, future) = requests.remove(dataS)
                ?: throw BadPeerException("Unknown or expired ping data in response: $dataS")
            future.complete(curTime() - sentT)
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext) {
            closed = true
            activeFuture.completeExceptionally(ConnectionClosedException())
            synchronized(requests) {
                requests.values.forEach { it.second.completeExceptionally(ConnectionClosedException()) }
                requests.clear()
            }
            super.channelUnregistered(ctx)
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            this.ctx = ctx
            activeFuture.complete(null)
        }

        override fun ping(): CompletableFuture<Long> {
            if (closed) return completedExceptionally(ConnectionClosedException())
            val ret = CompletableFuture<Long>()
            val data = ByteArray(pingSize)
            random.nextBytes(data)
            val dataS = data.toHex()
            requests[dataS] = curTime() to ret
            scheduler.schedule({
                requests.remove(dataS)?.second?.completeExceptionally(PingTimeoutException())
            }, pingTimeout.toMillis(), TimeUnit.MILLISECONDS)
            ctx.writeAndFlush(data.toByteBuf())
            return ret
        }
    }
}