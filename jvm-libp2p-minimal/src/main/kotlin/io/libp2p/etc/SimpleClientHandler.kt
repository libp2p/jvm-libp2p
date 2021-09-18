package io.libp2p.etc

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.util.concurrent.CompletableFuture

fun <T : SimpleClientHandler> createSimpleHandler(handlerCtor: () -> T): P2PChannelHandler<T> =
    SimpleClientProtocol(handlerCtor)

fun <T : SimpleClientHandler> createSimpleBinding(protocolName: String, handlerCtor: () -> T): ProtocolBinding<T> =
    ProtocolBinding.createSimple(protocolName, createSimpleHandler(handlerCtor))

abstract class SimpleClientHandler : SimpleChannelInboundHandler<ByteBuf>(ByteBuf::class.java) {
    val activeFuture = CompletableFuture<Unit>()
    protected lateinit var ctx: ChannelHandlerContext
    protected lateinit var stream: Stream

    open fun handlerCreated() {}
    open fun streamActive(ctx: ChannelHandlerContext) {}
    open fun streamClosed(ctx: ChannelHandlerContext) {}
    open fun messageReceived(ctx: ChannelHandlerContext, msg: ByteBuf) {}

    fun write(bb: ByteBuf) = ctx.write(bb).toVoidCompletableFuture()
    fun flush() = ctx.flush()
    fun writeAndFlush(bb: ByteBuf) = ctx.writeAndFlush(bb).toVoidCompletableFuture()

    fun initStream(stream: Stream) {
        this.stream = stream
        handlerCreated()
    }

    final override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        messageReceived(ctx, msg)
    }

    final override fun channelUnregistered(ctx: ChannelHandlerContext) {
        streamClosed(ctx)
        activeFuture.completeExceptionally(ConnectionClosedException())
    }

    final override fun channelActive(ctx: ChannelHandlerContext) {
        streamActive(ctx)
        activeFuture.complete(null)
    }
}

class SimpleClientProtocol<TController : SimpleClientHandler>(
    val handlerCtor: () -> TController
) : P2PChannelHandler<TController> {

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController> {
        val handler = handlerCtor()
        handler.initStream(ch as Stream)
        ch.pushHandler(handler)
        return handler.activeFuture.thenApply { handler }
    }
}
