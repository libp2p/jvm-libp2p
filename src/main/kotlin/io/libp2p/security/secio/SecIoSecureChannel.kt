package io.libp2p.security.secio

import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.REMOTE_PEER_ID
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture

private val log = LogManager.getLogger(SecIoSecureChannel::class.java)
private val HandshakeHandlerName = "SecIoHandshake"

class SecIoSecureChannel(private val localKey: PrivKey) : SecureChannel {
    override val announce = "/secio/1.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = "/secio/1.0.0")

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
        val handshakeComplete = CompletableFuture<SecureChannel.Session>()

        listOf(
            "PacketLenEncoder" to LengthFieldPrepender(4),
            "PacketLenDecoder" to LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
            HandshakeHandlerName to SecIoHandshake(localKey, handshakeComplete)
        ).forEach { ch.pushHandler(it.first, it.second) }

        return handshakeComplete
    }
} // class SecIoSecureChannel

private class SecIoHandshake(
    private val localKey: PrivKey,
    private val handshakeComplete: CompletableFuture<SecureChannel.Session>
) : SimpleChannelInboundHandler<ByteBuf>() {
    private lateinit var negotiator: SecIoNegotiator
    private var activated = false
    private lateinit var secIoCodec: SecIoCodec

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (!activated) {
            activated = true
            val remotePeerId = ctx.channel().attr(REMOTE_PEER_ID).get()
            negotiator =
                SecIoNegotiator({ buf -> writeAndFlush(ctx, buf) }, localKey, remotePeerId)
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
            val session = SecureChannel.Session(
                PeerId.fromPubKey(secIoCodec.local.permanentPubKey),
                PeerId.fromPubKey(secIoCodec.remote.permanentPubKey),
                secIoCodec.remote.permanentPubKey
            )
            handshakeComplete.complete(session)
            ctx.channel().pipeline().remove(HandshakeHandlerName)
            ctx.fireChannelActive()
        }
    }

    private fun writeAndFlush(ctx: ChannelHandlerContext, bb: ByteBuf) {
        ctx.writeAndFlush(bb)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handshakeComplete.completeExceptionally(cause)
        log.error("SecIo handshake failed", cause)
        ctx.channel().close()
    }

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        handshakeComplete.completeExceptionally(ConnectionClosedException("Connection was closed ${ctx.channel()}"))
        super.channelUnregistered(ctx)
    }
}
