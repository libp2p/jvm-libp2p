package io.libp2p.core

import io.libp2p.core.dsl.host
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.security.secio.SecIoSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * This is service to add or multiple numbers with corresponding protocol names:
 * - /my-calc/add/1.0.0
 * - /my-calc/mul/1.0.0
 *
 * Requester opens a stream, writes two longs (BE serialized),
 * Responder performs operation, sends one long (BE serialized) as result and resets the stream
 */
const val protoPrefix = "/my-calc"
const val protoAdd = "/add"
const val protoMul = "/mul"

class RpcProtocol(override val announce: String = "NOP") : ProtocolBinding<OpController> {
    override val matcher = ProtocolMatcher(Mode.PREFIX, protoPrefix)

    override fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<out OpController> {
        val ret = CompletableFuture<OpController>()
        val handler = if (ch.isInitiator) {
            OpClientHandler(ch as Stream, ret)
        } else {
            val op: (a: Long, b: Long) -> Long = when {
                selectedProtocol.indexOf(protoAdd) >= 0 -> { a, b -> a + b }
                selectedProtocol.indexOf(protoMul) >= 0 -> { a, b -> a * b }
                else -> throw IllegalArgumentException("Unknown op: $selectedProtocol")
            }
            OpServerHandler(op)
        }

        ch.nettyChannel.pipeline().addLast(handler)
        return ret
    }
}

interface OpController {
    fun calculate(a: Long, b: Long): CompletableFuture<Long> = throw NotImplementedError()
}

abstract class OpHandler : SimpleChannelInboundHandler<ByteBuf>(), OpController

class OpServerHandler(val op: (a: Long, b: Long) -> Long) : OpHandler() {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val res = op(msg.readLong(), msg.readLong())
        ctx.writeAndFlush(Unpooled.buffer().writeLong(res))
        ctx.close()
    }
}

class OpClientHandler(val stream: Stream, val activationFut: CompletableFuture<OpController>) : OpHandler() {
    private val resFuture = CompletableFuture<Long>()

    override fun channelActive(ctx: ChannelHandlerContext?) {
        activationFut.complete(this)
    }

    override fun calculate(a: Long, b: Long): CompletableFuture<Long> {
        stream.nettyChannel.writeAndFlush(Unpooled.buffer().writeLong(a).writeLong(b))
        return resFuture
    }
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        resFuture.complete(msg.readLong())
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        activationFut.completeExceptionally(cause)
        resFuture.completeExceptionally(cause)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        activationFut.completeExceptionally(ConnectionClosedException())
        resFuture.completeExceptionally(ConnectionClosedException())
    }
}

class RpcHandlerTest {

    @Test
    fun test1() {
        val host1 = host {
            identity {
                random()
            }
            transports {
                +::TcpTransport
            }
            secureChannels {
                add(::SecIoSecureChannel)
            }
            muxers {
                +::MplexStreamMuxer
            }
            protocols {
                +RpcProtocol()
            }
            debug {
                muxFramesHandler.setLogger(LogLevel.ERROR, "Host-1")
            }
        }

        val host2 = host {
            identity {
                random()
            }
            transports {
                +::TcpTransport
            }
            secureChannels {
                add(::SecIoSecureChannel)
            }
            muxers {
                +::MplexStreamMuxer
            }
            protocols {
                +RpcProtocol()
            }
            network {
                listen("/ip4/0.0.0.0/tcp/40002")
            }
            debug {
                muxFramesHandler.setLogger(LogLevel.ERROR, "Host-2")
            }
        }

        val start1 = host1.start()
        val start2 = host2.start()
        start1.get(5, TimeUnit.SECONDS)
        println("Host #1 started")
        start2.get(5, TimeUnit.SECONDS)
        println("Host #2 started")

        var streamCounter1 = 0
        host1.addStreamHandler(StreamHandler.create {
            streamCounter1++
        })
        var streamCounter2 = 0
        host2.addStreamHandler(StreamHandler.create {
            streamCounter2++
        })

        run {
            val ctr = host1.newStream<OpController>(protoPrefix + protoAdd, host2.peerId, Multiaddr("/ip4/127.0.0.1/tcp/40002"))
                .controler.get(5, TimeUnit.SECONDS)
            println("Controller created")
            val res = ctr.calculate(100, 10).get(5, TimeUnit.SECONDS)
            println("Calculated plus: $res")
            Assertions.assertEquals(110, res)
        }

        Assertions.assertEquals(1, host1.network.connections.size)
        Assertions.assertEquals(1, host2.network.connections.size)
        Assertions.assertEquals(1, streamCounter2)
        Assertions.assertEquals(1, streamCounter1)
        for (i in 1..100) {
            if (host1.streams.isNotEmpty() || host2.streams.isNotEmpty()) Thread.sleep(10)
            else break
        }
        Assertions.assertEquals(0, host1.streams.size)
        Assertions.assertEquals(0, host2.streams.size)
        val connection = host1.network.connections[0]

        run {
            val ctr = host1.newStream<OpController>(protoPrefix + protoMul, host2.peerId, Multiaddr("/ip4/127.0.0.1/tcp/40002"))
                .controler.get(5, TimeUnit.SECONDS)
            println("Controller created")
            val res = ctr.calculate(100, 10).get(5, TimeUnit.SECONDS)
            println("Calculated mul: $res")
            Assertions.assertEquals(1000, res)
        }

        Assertions.assertEquals(1, host1.network.connections.size)
        Assertions.assertEquals(1, host2.network.connections.size)
        Assertions.assertEquals(connection, host1.network.connections[0])
        Assertions.assertEquals(2, streamCounter1)
        Assertions.assertEquals(2, streamCounter2)
        for (i in 1..100) {
            if (host1.streams.isNotEmpty() || host2.streams.isNotEmpty()) Thread.sleep(10)
            else break
        }
        Assertions.assertEquals(0, host1.streams.size)
        Assertions.assertEquals(0, host2.streams.size)

        host1.stop().get(5, TimeUnit.SECONDS)
        println("Host #1 stopped")
        host2.stop().get(5, TimeUnit.SECONDS)
        println("Host #2 stopped")

        Assertions.assertEquals(0, host1.network.connections.size)
        Assertions.assertEquals(0, host2.network.connections.size)
    }
}