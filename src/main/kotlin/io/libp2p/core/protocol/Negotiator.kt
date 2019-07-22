package io.libp2p.core.protocol

import io.libp2p.core.events.ProtocolNegotiationFailed
import io.libp2p.core.events.ProtocolNegotiationSucceeded
import io.libp2p.core.util.netty.StringSuffixCodec
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
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
    private const val MULTISTREAM_PROTO = Protocols.MULTISTREAM_1_0_0

    private val NA = "na"
    private val LS = "ls"

    fun createInitializer(initiator: Boolean, vararg protocols: String): ChannelInitializer<Channel> {
        return nettyInitializer {
            initNegotiator(it, initiator, *protocols)
        }
    }

    /**
     * Negotiate as an initiator.
     */
    fun initNegotiator(ch: Channel, initiator: Boolean, vararg protocols: String) {
        if (protocols.isEmpty()) throw ProtocolNegotiationException("No protocols provided")

        val prehandlers = listOf(
            ReadTimeoutHandler(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            ProtobufVarint32FrameDecoder(),
            ProtobufVarint32LengthFieldPrepender(),
            StringDecoder(Charsets.UTF_8),
            StringEncoder(Charsets.UTF_8),
            StringSuffixCodec('\n')
        )

        prehandlers.forEach { ch.pipeline().addLast(it) }

        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
            var i = 0
            var headerRead = false

            override fun channelActive(ctx: ChannelHandlerContext) {
                ctx.write(MULTISTREAM_PROTO)
                if (initiator) ctx.write(protocols[0])
                ctx.flush()
            }

            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                msg as String
                var completeEvent: Any? = null
                when {
                    msg == MULTISTREAM_PROTO ->
                        if (!headerRead) headerRead = true else
                            throw ProtocolNegotiationException("Received multistream header more than once")
                    msg == LS -> {
                        protocols.forEach { ctx.write(it) }
                        ctx.flush()
                    }
                    initiator && (i == protocols.lastIndex || msg == protocols[i]) -> {
                        completeEvent = if (msg == protocols[i]) ProtocolNegotiationSucceeded(msg)
                        else ProtocolNegotiationFailed(protocols.toList())
                    }
                    !initiator && protocols.contains(msg) -> {
                        ctx.writeAndFlush(msg)
                        completeEvent = ProtocolNegotiationSucceeded(msg)
                    }
                    initiator -> ctx.run {
                        writeAndFlush(protocols[++i])
                    }
                    !initiator -> {
                        ctx.writeAndFlush(NA)
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