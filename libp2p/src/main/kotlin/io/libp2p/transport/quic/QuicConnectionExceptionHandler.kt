package io.libp2p.transport.quic

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.quic.QuicException
import org.slf4j.LoggerFactory

class QuicConnectionExceptionHandler : ChannelInboundHandlerAdapter() {
    private val logger = LoggerFactory.getLogger(QuicConnectionExceptionHandler::class.java)

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is QuicException) {
            logger.debug(
                "QUIC connection error on channel {} (local={}, remote={}), closing",
                ctx.channel().id(),
                ctx.channel().localAddress(),
                ctx.channel().remoteAddress(),
                cause
            )
            ctx.close()
        } else {
            ctx.fireExceptionCaught(cause)
        }
    }
}
