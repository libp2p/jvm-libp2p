package io.libp2p.core.protocol

import io.libp2p.core.BadPeerException
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.STREAM
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolBindingInitializer
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.types.forward
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.types.toHex
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import java.time.Duration
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface PingController {
    fun ping(): CompletableFuture<Long>
}

class PingBinding(val ping: PingProtocol): ProtocolBinding<PingController> {
    override val announce = "/ipfs/ping/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = announce)


    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<PingController> {
        val fut = CompletableFuture<PingController>()
        val initializer = nettyInitializer {
            ping.initChannel(it.attr(STREAM).get()).forward(fut)
        }
        return ProtocolBindingInitializer(initializer, fut)
    }
}

class PingTimeoutException: Libp2pException()

class PingProtocol: P2PAbstractHandler<PingController> {
    var scheduler  by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var curTime: () -> Long = { System.currentTimeMillis() }
    var random = Random()
    var pingSize = 32
    var pingTimeout = Duration.ofSeconds(10)

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<PingController> {
        return if (ch.isInitiator) {
            val handler = PingInitiatorChannelHandler()
            ch.ch.pipeline().addLast(handler)
            handler.activeFuture.thenApply { handler }
        } else {
            val handler = PingResponderChannelHandler()
            ch.ch.pipeline().addLast(handler)
            CompletableFuture.completedFuture(handler)
        }
    }

    inner class PingResponderChannelHandler: ChannelInboundHandlerAdapter(), PingController {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            ctx.writeAndFlush(msg)
        }

        override fun ping(): CompletableFuture<Long> {
            throw Libp2pException("This is ping responder only")
        }
    }

    inner class PingInitiatorChannelHandler: SimpleChannelInboundHandler<ByteBuf>(), PingController {
        val activeFuture = CompletableFuture<Unit>()
        val requests = ConcurrentHashMap<String, Pair<Long, CompletableFuture<Long>>>()
        lateinit var ctx: ChannelHandlerContext

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            val dataS = msg.toByteArray().toHex()
            val (sentT, future) = requests.remove(dataS)
                ?: throw BadPeerException("Unknown or expired ping data in response: $dataS")
            future.complete(curTime() - sentT)
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext) {
            activeFuture.completeExceptionally(ConnectionClosedException())
            super.channelUnregistered(ctx)
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            this.ctx = ctx
            activeFuture.complete(null)
        }

        override fun ping(): CompletableFuture<Long> {
            val ret = CompletableFuture<Long>()
            val data = ByteArray(pingSize)
            random.nextBytes(data)
            val dataS = data.toHex()
            requests[dataS] = curTime() to ret
            scheduler.schedule( {
                requests.remove(dataS)?.second?.completeExceptionally(PingTimeoutException())
            }, pingTimeout.toMillis(), TimeUnit.MILLISECONDS)
            ctx.writeAndFlush(data.toByteBuf())
            return ret
        }
    }
}