package io.libp2p.protocol

import identify.pb.IdentifyOuterClass
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolMatcher
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandler
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.protobuf.ProtobufDecoder
import io.netty.handler.codec.protobuf.ProtobufEncoder
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import java.util.concurrent.CompletableFuture

interface IdentifyController {
    fun id(): CompletableFuture<IdentifyOuterClass.Identify>
}

class Identify : IdentifyBinding(IdentifyProtocol())

open class IdentifyBinding(val ping: IdentifyProtocol) : ProtocolBinding<IdentifyController> {
    override val announce = "/ipfs/id/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = announce)

    override fun initChannel(
        ch: P2PAbstractChannel,
        selectedProtocol: String
    ): CompletableFuture<out IdentifyController> {
        return ping.initChannel(ch)
    }
}

class IdentifyProtocol : P2PAbstractHandler<IdentifyController> {

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<IdentifyController> {
        val handler: Handler = if (ch.isInitiator) {
            IdentifyRequesterChannelHandler()
        } else {
            IdentifyResponderChannelHandler()
        }

        with(ch.nettyChannel.pipeline()) {
            addLast(ProtobufVarint32FrameDecoder())
            addLast(ProtobufVarint32LengthFieldPrepender())
            addLast(ProtobufDecoder(IdentifyOuterClass.Identify.getDefaultInstance()))
            addLast(ProtobufEncoder())
        }
        ch.nettyChannel.pipeline().addLast(handler)
        return CompletableFuture.completedFuture(handler)
    }

    interface Handler : ChannelInboundHandler, IdentifyController

    inner class IdentifyResponderChannelHandler : SimpleChannelInboundHandler<IdentifyOuterClass.Identify>(), Handler {

        override fun channelActive(ctx: ChannelHandlerContext) {
            val msg = IdentifyOuterClass.Identify.newBuilder()
                .setAgentVersion("Java-Harmony-0.1.0")
                .build()
            ctx.writeAndFlush(msg)
            ctx.close()
        }

        override fun channelRead0(ctx: ChannelHandlerContext?, msg: IdentifyOuterClass.Identify?) {
            throw Libp2pException("No inbounds expected here")
        }

        override fun id(): CompletableFuture<IdentifyOuterClass.Identify> {
            throw Libp2pException("This is Identify responder only")
        }
    }

    inner class IdentifyRequesterChannelHandler : SimpleChannelInboundHandler<IdentifyOuterClass.Identify>(), Handler {

        private val resp = CompletableFuture<IdentifyOuterClass.Identify>()

        override fun channelRead0(ctx: ChannelHandlerContext, msg: IdentifyOuterClass.Identify) {
            resp.complete(msg)
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext?) {
            resp.completeExceptionally(ConnectionClosedException())
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
            resp.completeExceptionally(cause)
        }

        override fun id(): CompletableFuture<IdentifyOuterClass.Identify> = resp
    }
}