package io.libp2p.transport.quic

import io.libp2p.etc.types.hasCauseOfType
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.quic.QuicChannel
import io.netty.handler.codec.quic.QuicException
import org.slf4j.LoggerFactory

/**
 * Consumes QUIC transport failures (logged at debug, connection closed) so they do not reach the
 * pipeline tail. Netty reports QUICHE_ERR_CRYPTO_FAIL as an SSLException and QUICHE_ERR_TLS_FAIL as an
 * SSLHandshakeException, each wrapping the QuicException, hence the cause-chain check.
 */
class QuicConnectionExceptionHandler : ChannelInboundHandlerAdapter() {
    private val logger = LoggerFactory.getLogger(QuicConnectionExceptionHandler::class.java)

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause.hasCauseOfType(QuicException::class)) {
            val channel = ctx.channel()
            logger.debug(
                "QUIC connection error on channel {} (local={}, remote={}), closing",
                channel.id(),
                channel.localAddress(),
                (channel as? QuicChannel)?.remoteSocketAddress() ?: channel.remoteAddress(),
                cause
            )
            ctx.close()
        } else {
            ctx.fireExceptionCaught(cause)
        }
    }
}
