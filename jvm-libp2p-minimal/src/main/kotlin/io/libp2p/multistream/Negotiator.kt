package io.libp2p.multistream

import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.etc.events.ProtocolNegotiationFailed
import io.libp2p.etc.events.ProtocolNegotiationSucceeded
import io.libp2p.etc.util.netty.NettyInit
import io.libp2p.etc.util.netty.StringSuffixCodec
import io.libp2p.etc.util.netty.TotalTimeoutHandler
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import java.time.Duration

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
 * The negotiation is expected to complete within specified time limit else the connection is closed
 */
object Negotiator {
    private const val MULTISTREAM_PROTO = "/multistream/1.0.0"

    private val MESSAGE_SUFFIX = '\n'
    private val NA = "na"
    private val LS = "ls"

    val MAX_MULTISTREAM_MESSAGE_LENGTH = 1024
    val MESSAGE_SUFFIX_LENGTH = MESSAGE_SUFFIX.toString().toByteArray(Charsets.UTF_8).size
    val MAX_PROTOCOL_ID_LENGTH = MAX_MULTISTREAM_MESSAGE_LENGTH - MESSAGE_SUFFIX_LENGTH

    fun createRequesterInitializer(negotiationTimeLimit: Duration, vararg protocols: String): ChannelInitializer<Channel> {
        return nettyInitializer {
            initNegotiator(
                it,
                RequesterHandler(listOf(*protocols), negotiationTimeLimit)
            )
        }
    }

    fun createResponderInitializer(negotiationTimeLimit: Duration, protocols: List<ProtocolMatcher>): ChannelInitializer<Channel> {
        return nettyInitializer {
            initNegotiator(
                it,
                ResponderHandler(protocols, negotiationTimeLimit)
            )
        }
    }

    fun initNegotiator(ch: NettyInit, handler: GenericHandler) {
        handler.prehandlers.forEach { ch.addLastLocal(it) }
        ch.addLastLocal(handler)
    }

    abstract class GenericHandler(val negotiationTimeLimit: Duration) : SimpleChannelInboundHandler<String>() {
        open val initialProtocolAnnounce: String? = null

        val prehandlers = listOf(
            TotalTimeoutHandler(negotiationTimeLimit),
            LimitedProtobufVarint32FrameDecoder(MAX_MULTISTREAM_MESSAGE_LENGTH),
            ProtobufVarint32LengthFieldPrepender(),
            StringDecoder(Charsets.UTF_8),
            StringEncoder(Charsets.UTF_8),
            StringSuffixCodec(MESSAGE_SUFFIX)
        )

        var headerRead = false

        override fun channelActive(ctx: ChannelHandlerContext) {
            ctx.write(MULTISTREAM_PROTO)
            initialProtocolAnnounce?.also { ctx.write(it) }
            ctx.flush()
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
            if (msg == MULTISTREAM_PROTO) {
                if (!headerRead) headerRead = true else
                    throw ProtocolNegotiationException("Received multistream header more than once")
            } else {
                processMsg(ctx, msg)?.also { completeEvent ->
                    // first fire event to setup a handler for selected protocol
                    ctx.fireUserEventTriggered(completeEvent)
                    ctx.pipeline().remove(this@GenericHandler)
                    // DelimiterBasedFrameDecoder should be removed last since it
                    // propagates unhandled bytes on removal
                    prehandlers.reversed().forEach { ctx.pipeline().remove(it) }
                    // activate a handler for selected protocol
                    ctx.fireChannelActive()
                }
            }
        }

        protected abstract fun processMsg(ctx: ChannelHandlerContext, msg: String): Any?
    }

    class RequesterHandler(val protocols: List<String>, negotiationTimeLimit: Duration) : GenericHandler(negotiationTimeLimit) {
        override val initialProtocolAnnounce = protocols[0]
        var i = 0

        init {
            protocols.forEach { require(it.length <= MAX_PROTOCOL_ID_LENGTH) { "Too long protocol ID: '$it'" } }
        }

        override fun processMsg(ctx: ChannelHandlerContext, msg: String): Any? {
            return when {
                msg == protocols[i] -> ProtocolNegotiationSucceeded(msg)
                i == protocols.lastIndex -> ProtocolNegotiationFailed(protocols.toList())
                else -> {
                    ctx.writeAndFlush(protocols[++i])
                    null
                }
            }
        }
    }

    class ResponderHandler(val protocols: List<ProtocolMatcher>, negotiationTimeLimit: Duration) : GenericHandler(negotiationTimeLimit) {
        override fun processMsg(ctx: ChannelHandlerContext, msg: String): Any? {
            return when {
                protocols.any { it.matches(msg) } -> {
                    ctx.writeAndFlush(msg)
                    ProtocolNegotiationSucceeded(msg)
                }
                else -> {
                    ctx.writeAndFlush(NA)
                    null
                }
            }
        }
    }
}
