package io.libp2p.security.secio

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.REMOTE_PEER_ID
import io.libp2p.etc.events.SecureChannelFailed
import io.libp2p.etc.events.SecureChannelInitialized
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture

class SecIoSecureChannel(val localKey: PrivKey) :
    SecureChannel {

    private val log = LogManager.getLogger(SecIoSecureChannel::class.java)

    private val HandshakeHandlerName = "SecIoHandshake"
    private val HadshakeTimeout = 30 * 1000L

    override val announce = "/secio/1.0.0"
    override val matcher =
        ProtocolMatcher(Mode.STRICT, name = "/secio/1.0.0")

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
        val ret = CompletableFuture<SecureChannel.Session>()
        val resultHandler = object : ChannelInboundHandlerAdapter() {
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                when (evt) {
                    is SecureChannelInitialized -> {
                        ret.complete(evt.session)
                        ctx.pipeline().remove(this)
                    }
                    is SecureChannelFailed -> {
                        ret.completeExceptionally(evt.exception)
                        ctx.pipeline().remove(this)
                    }
                }
                ctx.fireUserEventTriggered(evt)
            }
        }
        listOf(
            "PacketLenEncoder" to LengthFieldPrepender(4),
            "PacketLenDecoder" to LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
            HandshakeHandlerName to SecIoHandshake(),
            "SecioNegotiationResultHandler" to resultHandler
        ).forEach { ch.pushHandler(it.first, it.second) }
        return ret
    }

    inner class SecIoHandshake : SimpleChannelInboundHandler<ByteBuf>() {
        private lateinit var negotiator: SecioHandshake
        private var activated = false
        private lateinit var secIoCodec: SecIoCodec

        override fun channelActive(ctx: ChannelHandlerContext) {
            if (!activated) {
                activated = true
                val remotePeerId = ctx.channel().attr(REMOTE_PEER_ID).get()
                negotiator =
                    SecioHandshake({ buf -> writeAndFlush(ctx, buf) }, localKey, remotePeerId)
                negotiator.start()
            }
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
            // it seems there is no guarantee from Netty that channelActive() must be called before channelRead()
            channelActive(ctx)

            val keys = negotiator.onNewMessage(msg)

            if (keys != null) {
                secIoCodec = SecIoCodec(keys.first, keys.second)
                ctx.channel().pipeline().addBefore(HandshakeHandlerName, "SecIoCodec", secIoCodec)
                negotiator.onSecureChannelSetup()
            }

            if (negotiator.isComplete()) {
                val session = SecioSession(
                    PeerId.fromPubKey(secIoCodec.local.permanentPubKey),
                    PeerId.fromPubKey(secIoCodec.remote.permanentPubKey),
                    secIoCodec.remote.permanentPubKey
                )
                ctx.fireUserEventTriggered(SecureChannelInitialized(session))
                ctx.channel().pipeline().remove(HandshakeHandlerName)
                ctx.fireChannelActive()
            }
        }

        private fun writeAndFlush(ctx: ChannelHandlerContext, bb: ByteBuf) {
            ctx.writeAndFlush(bb)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.fireUserEventTriggered(SecureChannelFailed(cause))
            log.error(cause.message)
            ctx.channel().close()
        }

        override fun channelUnregistered(ctx: ChannelHandlerContext) {
            ctx.fireUserEventTriggered(SecureChannelFailed(ConnectionClosedException("Connection was closed ${ctx.channel()}")))
            super.channelUnregistered(ctx)
        }
    }
}

/**
 * SecioSession exposes the identity and public security material of the other party as authenticated by SecIO.
 */
class SecioSession(localId: PeerId, remoteId: PeerId, remotePubKey: PubKey) :
    SecureChannel.Session(localId, remoteId, remotePubKey)