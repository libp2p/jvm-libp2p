package io.libp2p.core

import io.libp2p.core.types.toVoidCompletableFuture
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.util.concurrent.CompletableFuture

interface P2PAbstractHandler<out TController> {
    fun initChannel(ch: P2PAbstractChannel): CompletableFuture<out TController>

    fun toStreamHandler() = object : StreamHandler {
        override fun handleStream(stream: Stream) {
            initChannel(stream)
        }
    }

    companion object {
        fun <T : SimpleClientHandler> createSimpleHandler(handlerCtor: () -> T) =
            SimpleClientProtocol(handlerCtor)
    }
}

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
) : P2PAbstractHandler<TController> {

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<TController> {
        val handler = handlerCtor()
        handler.initStream(ch as Stream)
        ch.nettyChannel.pipeline().addLast(handler)
        return handler.activeFuture.thenApply { handler }
    }
}