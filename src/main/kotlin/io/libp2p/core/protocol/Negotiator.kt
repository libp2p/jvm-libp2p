package io.libp2p.core.protocol

import io.libp2p.core.events.ProtocolNegotiationFailed
import io.libp2p.core.events.ProtocolNegotiationSucceeded
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.handler.codec.DelimiterBasedFrameDecoder
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
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

    private val HEADING = '\u0013'
    private val NEWLINE = "\n"
    private val DELIMITER = "\r"
    private val NA = "na"
    private val DELIMITERS = arrayOf(
        wrappedBuffer("\n\r".toByteArray()),
        wrappedBuffer("\n".toByteArray())
    )

    fun createInitializer(initiator: Boolean, vararg protocols: String): ChannelInitializer<Channel> {
        return object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                initNegotiator(ch, initiator, *protocols)
            }
        }
    }

    /**
     * Negotiate as an initiator.
     */
    fun initNegotiator(ch: Channel, initiator: Boolean, vararg protocols: String) {
        if (protocols.isEmpty()) throw ProtocolNegotiationException("No protocols provided")

        val prehandlers = listOf(
            ReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            DelimiterBasedFrameDecoder(1024, *DELIMITERS),
            StringDecoder(Charsets.UTF_8),
            StringEncoder(Charsets.UTF_8)
        )

        prehandlers.forEach { ch.pipeline().addLast(it) }

        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            var i = 0
            var headerRead = false

            override fun channelActive(ctx: ChannelHandlerContext) {
                val helloString = HEADING + MULTISTREAM_PROTO + NEWLINE +
                        if (initiator) DELIMITER + protocols[0] + NEWLINE else ""
                ctx.writeAndFlush(helloString)
            }

            override fun channelRead(ctx: ChannelHandlerContext, msgRaw: Any) {
                val msg = (msgRaw as String).trimStart(HEADING)
                var completeEvent: Any? = null
                when {
                    msg == MULTISTREAM_PROTO ->
                        if (!headerRead) headerRead = true else
                            throw ProtocolNegotiationException("Received multistream header more than once")
                    initiator && (i == protocols.lastIndex || msg == protocols[i]) -> {
                        completeEvent = if (msg == protocols[i]) ProtocolNegotiationSucceeded(msg)
                        else ProtocolNegotiationFailed(protocols.toList())
                    }
                    !initiator && protocols.contains(msg) -> {
                        ctx.writeAndFlush(HEADING + msg + NEWLINE)
                        completeEvent = ProtocolNegotiationSucceeded(msg)
                    }
                    initiator -> ctx.run {
                        writeAndFlush(protocols[++i] + NEWLINE)
                    }
                    !initiator -> {
                        ctx.writeAndFlush(HEADING + NA + NEWLINE)
                    }
                }
                if (completeEvent != null) {
                    // first fire event to setup a handler for selected protocol
                    ctx.pipeline().fireUserEventTriggered(completeEvent)
                    ctx.pipeline().remove(this)
                    // DelimiterBasedFrameDecoder should be removed last since it
                    // propagates unhandled bytes on removal
                    prehandlers.reversed().forEach { ctx.pipeline().remove(it) }
                    // activate a handler for selected protocol
                    ctx.fireChannelActive()
                }
            }
        })
    }
}