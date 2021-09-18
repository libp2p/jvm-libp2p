package io.libp2p.tools

import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.apache.logging.log4j.LogManager

open class TestHandler(val name: String = "") : ChannelInboundHandlerAdapter() {
    override fun channelActive(ctx: ChannelHandlerContext) {
        logger.debug("==$name== Active")
        super.channelActive(ctx)
    }

    override fun channelRegistered(ctx: ChannelHandlerContext?) {
        logger.debug("==$name== channelRegistered")
        super.channelRegistered(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
        logger.debug("==$name== exceptionCaught: $cause")
        super.exceptionCaught(ctx, cause)
    }

    override fun handlerAdded(ctx: ChannelHandlerContext?) {
        logger.debug("==$name== handlerAdded")
        super.handlerAdded(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
        val content = when (msg) {
//            is ByteBuf -> msg.toByteArray().toHex() + "(" + msg.toByteArray().toString(StandardCharsets.UTF_8) + ")"
            is ByteBuf -> msg.toByteArray().toHex() + "(" + msg.readableBytes() + ")"
            else -> msg.toString()
        }
        logger.debug("==$name== read: $content")
        super.channelRead(ctx, msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext?) {
        logger.debug("==$name== channelInactive")
        super.channelInactive(ctx)
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext?) {
        logger.debug("==$name== channelUnregistered")
        super.channelUnregistered(ctx)
    }

    companion object {
        private val logger = LogManager.getLogger(TestHandler::class.java)
    }
}
