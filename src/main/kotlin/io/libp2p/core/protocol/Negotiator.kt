package io.libp2p.core.protocol

import io.libp2p.core.events.ProtocolNegotiationFailed
import io.libp2p.core.events.ProtocolNegotiationSucceeded
import io.libp2p.core.types.toByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.DelimiterBasedFrameDecoder
import io.netty.handler.codec.Delimiters
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.timeout.ReadTimeoutHandler
import java.util.concurrent.TimeUnit

/**
 * This exception signals that protocol negotiation errored unexpectedly.
 */
class ProtocolNegotiationException(message: String) : RuntimeException(message)

/**
 * Negotiator offers routines to perform protocol negotiation as an initiator or as a responder.
 *
 * As an initiator, we send the `/multistream/1.0.0` announcement immediately followed by our first proposal. The
 * responder then echoes the protocol to accept, or returns `na` to reject. In the latter case, we propose the
 * next option.
 *
 * As a responder, we also send the `/multistream/1.0.0` announcement immediately, but we await proposals and
 * respond to them based on our known protocols.
 *
 * In all cases, if protocol negotiation succeeds with mutual agreement, we emit the [ProtocolNegotiationSucceeded]
 * user event on the context. If we exhaust all our options without agreement, we emit the [ProtocolNegotiationFailed]
 * event.
 *
 * We set a read timeout of 10 seconds.
 */
object Negotiator {
    private const val TIMEOUT_MILLIS: Long = 10_000
    private const val MULTISTREAM_PROTO = "/multistream/1.0.0"

    private val MULTISTREAM_PROTO_BYTES = MULTISTREAM_PROTO.toByteArray().toByteBuf()
    private val NEWLINE = "\n".toByteArray().toByteBuf()
    private val NA = "na".toByteArray().toByteBuf()

    /**
     * Negotiate as an initiator.
     */
    fun asInitiator(ch: Channel, vararg protocols: String) {
        if (protocols.isEmpty()) throw ProtocolNegotiationException("No protocols provided")

        val prehandlers = listOf(
            ReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            DelimiterBasedFrameDecoder(1024, *Delimiters.lineDelimiter()),
            StringDecoder(Charsets.UTF_8)
        )

        prehandlers.forEach { ch.pipeline().addLast(it) }

        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            var i = 0
            var headerRead = false

            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                when {
                    msg as String == MULTISTREAM_PROTO ->
                        if (!headerRead) headerRead = true else
                            throw ProtocolNegotiationException("Received multistream header more than once")
                    msg == protocols[i] -> {
                        prehandlers.forEach { ctx.pipeline().remove(it) }
                        ctx.pipeline().remove(this)
                        ctx.pipeline().fireUserEventTriggered(ProtocolNegotiationSucceeded(msg))
                    }
                    i == protocols.lastIndex -> {
                        prehandlers.forEach { ctx.pipeline().remove(it) }
                        ctx.pipeline().remove(this)
                        ctx.pipeline().fireUserEventTriggered(ProtocolNegotiationFailed(protocols.toList()))
                    }
                    else -> ctx.run {
                        write(protocols[++i].toByteArray().toByteBuf())
                        writeAndFlush(NEWLINE)
                    }
                }
            }
        })

        ch.run {
            write(MULTISTREAM_PROTO_BYTES)
            write(NEWLINE)
            write(protocols[0].toByteArray().toByteBuf())
            write(NEWLINE)
        }
    }

    /**
     * Negotiate as a responder.
     */
    fun asResponder(ch: Channel, supported: List<String>) {
        if (supported.isEmpty()) throw ProtocolNegotiationException("No protocols provided")

        val prehandlers = listOf(
            ReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            DelimiterBasedFrameDecoder(1024, *Delimiters.lineDelimiter()),
            StringDecoder(Charsets.UTF_8)
        )

        prehandlers.forEach { ch.pipeline().addLast(it) }

        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            var headerRead = false
            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                when {
                    msg as String == MULTISTREAM_PROTO ->
                        if (!headerRead) headerRead = true else
                            throw ProtocolNegotiationException("Received multistream header more than once")
                    supported.contains(msg) -> {
                        ctx.write(msg)
                        ctx.writeAndFlush(NEWLINE)
                        prehandlers.forEach { ctx.pipeline().remove(it) }
                        ctx.pipeline().remove(this)
                        ctx.pipeline().fireUserEventTriggered(ProtocolNegotiationSucceeded(msg))
                    }
                    else -> {
                        // TODO: cap the maximum inbound attempts.
                        ctx.write(NA)
                        ctx.writeAndFlush(NEWLINE)
                    }
                }
            }
        })

        ch.run {
            write(MULTISTREAM_PROTO_BYTES)
            writeAndFlush(NEWLINE)
        }
    }
}