package io.libp2p.security.plaintext

import crypto.pb.Crypto
import io.libp2p.core.ConnectionClosedException
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.types.toProtobuf
import io.libp2p.security.secio.InvalidInitialPacket
import io.libp2p.security.secio.InvalidRemotePubKey
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import plaintext.pb.Plaintext
import java.util.concurrent.CompletableFuture

class PlaintextInsecureChannel(private val localKey: PrivKey) : SecureChannel {
    override val announce = "/plaintext/2.0.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = announce)

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out SecureChannel.Session> {
        val handshakeCompleted = CompletableFuture<SecureChannel.Session>()

        val handshaker = PlaintextHandshakeHandler(handshakeCompleted, localKey)
        listOf(
            LengthFieldPrepender(4),
            LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4),
            handshaker
        ).forEach { ch.pushHandler(it) }

        return handshakeCompleted
    }
} // PlaintextInsecureChannel

class PlaintextHandshakeHandler(
    private val handshakeCompleted: CompletableFuture<SecureChannel.Session>,
    localKey: PrivKey
) : SimpleChannelInboundHandler<ByteBuf>() {
    private val localPubKey = localKey.publicKey()
    private val localPeerId = PeerId.fromPubKey(localPubKey)
    private lateinit var remotePubKey: PubKey
    private lateinit var remotePeerId: PeerId

    override fun channelActive(ctx: ChannelHandlerContext) {
        val pubKeyMsg = Crypto.PublicKey.newBuilder()
            .setType(localPubKey.keyType)
            .setData(localPubKey.bytes().toProtobuf())
            .build()

        val exchangeMsg = Plaintext.Exchange.newBuilder()
            .setId(localPeerId.bytes.toProtobuf())
            .setPubkey(pubKeyMsg)
            .build()

        val byteBuf = Unpooled.buffer().writeBytes(exchangeMsg.toByteArray())
        ctx.writeAndFlush(byteBuf)
    } // channelActive

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        val exchangeRecv = Plaintext.Exchange.parser().parseFrom(msg.nioBuffer())
            ?: throw InvalidInitialPacket()

        if (!exchangeRecv.hasPubkey())
            throw InvalidRemotePubKey()

        remotePeerId = PeerId(exchangeRecv.id.toByteArray())
        remotePubKey = unmarshalPublicKey(exchangeRecv.pubkey.data.toByteArray())
        val calculatedPeerId = PeerId.fromPubKey(remotePubKey)
        if (remotePeerId != calculatedPeerId)
            throw InvalidRemotePubKey()

        handshakeCompleted(ctx)
    } // channelRead0

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handshakeFailed(cause)
        ctx.channel().close()
    } // exceptionCaught

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        handshakeFailed(ConnectionClosedException("Connection was closed ${ctx.channel()}"))
        super.channelUnregistered(ctx)
    } // channelUnregistered

    private fun handshakeCompleted(ctx: ChannelHandlerContext) {
        val session = SecureChannel.Session(
            localPeerId,
            remotePeerId,
            remotePubKey
        )

        ctx.channel().attr(SECURE_SESSION).set(session)
        handshakeCompleted.complete(session)
        ctx.pipeline().remove(this)
        ctx.fireChannelActive()
    } // handshakeComplete

    private fun handshakeFailed(cause: Throwable) {
        handshakeCompleted.completeExceptionally(cause)
    } // handshakeFailed
} // PlaintextHandshakeHandler
