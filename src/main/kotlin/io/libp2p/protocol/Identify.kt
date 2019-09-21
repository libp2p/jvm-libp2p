package io.libp2p.protocol

import identify.pb.IdentifyOuterClass
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
import io.libp2p.core.Stream
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.types.toProtobuf
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

class Identify(idMessage: IdentifyOuterClass.Identify? = null) : IdentifyBinding(IdentifyProtocol(idMessage))

open class IdentifyBinding(val protocol: IdentifyProtocol) : ProtocolBinding<IdentifyController> {
    override val announce = "/ipfs/id/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = announce)

    override fun initChannel(
        ch: P2PAbstractChannel,
        selectedProtocol: String
    ): CompletableFuture<out IdentifyController> {
        return protocol.initChannel(ch)
    }
}

class IdentifyProtocol(var idMessage: IdentifyOuterClass.Identify? = null) : P2PAbstractHandler<IdentifyController> {

    override fun initChannel(ch: P2PAbstractChannel): CompletableFuture<IdentifyController> {
        val handler: Handler = if (ch.isInitiator) {
            IdentifyRequesterChannelHandler()
        } else {
            IdentifyResponderChannelHandler((ch as Stream).conn.remoteAddress())
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

    inner class IdentifyResponderChannelHandler(val remoteAddr: Multiaddr) : SimpleChannelInboundHandler<IdentifyOuterClass.Identify>(), Handler {

        override fun channelActive(ctx: ChannelHandlerContext) {
            val msg = idMessage ?: IdentifyOuterClass.Identify.newBuilder()
                .setAgentVersion("jvm/0.1")
                .build()

            val msgWithAddr = msg.toBuilder().setObservedAddr(remoteAddr.getBytes().toProtobuf()).build()

            ctx.writeAndFlush(msgWithAddr)
            ctx.disconnect()
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