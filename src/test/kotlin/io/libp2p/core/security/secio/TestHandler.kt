package io.libp2p.core.security.secio

import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toHex
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter

open class TestHandler(val name: String = ""): ChannelInboundHandlerAdapter() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        println("==$name== Active")
        super.channelActive(ctx)
    }

    override fun channelRegistered(ctx: ChannelHandlerContext?) {
        println("==$name== channelRegistered")
        super.channelRegistered(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        println("==$name== exceptionCaught: $cause")
        super.exceptionCaught(ctx, cause)
    }

    override fun handlerAdded(ctx: ChannelHandlerContext?) {
        println("==$name== handlerAdded")
        super.handlerAdded(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
        val content = when(msg) {
//            is ByteBuf -> msg.toByteArray().toHex() + "(" + msg.toByteArray().toString(StandardCharsets.UTF_8) + ")"
            is ByteBuf -> msg.toByteArray().toHex() + "(" + msg.readableBytes() + ")"
            else -> msg.toString()
        }
        println("==$name== read: $content")
        super.channelRead(ctx, msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext?) {
        println("==$name== channelInactive")
        super.channelInactive(ctx)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        println("==$name== channelUnregistered")
        super.channelUnregistered(ctx)
    }
}