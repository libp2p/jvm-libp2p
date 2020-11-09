package io.libp2p.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.ProtocolDescriptor
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toUShortBigEndian
import io.libp2p.etc.types.uShortToBytesBigEndian
import io.libp2p.etc.util.netty.SplitEncoder
import io.libp2p.security.InvalidRemotePubKey
import io.libp2p.security.SecureHandshakeError
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.CombinedChannelDuplexHandler
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import org.apache.logging.log4j.LogManager
import spipe.pb.Spipe
import java.util.concurrent.CompletableFuture

private enum class Role(val intVal: Int) { INIT(HandshakeState.INITIATOR), RESP(HandshakeState.RESPONDER) }

private val log = LogManager.getLogger(NoiseXXSecureChannel::class.java)
private const val HandshakeNettyHandlerName = "HandshakeNettyHandler"
private const val NoiseCodeNettyHandlerName = "NoiseXXCodec"
private const val MaxCipheredPacketLength = 65535

class UShortLengthCodec : CombinedChannelDuplexHandler<LengthFieldBasedFrameDecoder, LengthFieldPrepender>(
    LengthFieldBasedFrameDecoder(MaxCipheredPacketLength + 2, 0, 2, 0, 2),
    LengthFieldPrepender(2)
)

class NoiseXXSecureChannel(private val localKey: PrivKey) :
    SecureChannel {

    companion object {
        const val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val announce = "/noise"

        @JvmStatic
        var localStaticPrivateKey25519: ByteArray = ByteArray(32).also { Noise.random(it) }

        // temporary flag to handle different Noise handshake treatments
        // if true the noise handshake payload is prepended with 2 bytes encoded length
        @JvmStatic
        var rustInteroperability = false
    }

    override val protocolDescriptor = ProtocolDescriptor(announce)

    fun initChannel(ch: P2PChannel): CompletableFuture<SecureChannel.Session> {
        return initChannel(ch, "")
    } // initChannel

    override fun initChannel(
        ch: P2PChannel,
        selectedProtocol: String
    ): CompletableFuture<SecureChannel.Session> {
        val handshakeComplete = CompletableFuture<SecureChannel.Session>()
        // Packet length codec should stay forever.
        ch.pushHandler(UShortLengthCodec())
        // Handshake handle is to be removed when handshake is complete
        ch.pushHandler(
            HandshakeNettyHandlerName,
            NoiseIoHandshake(localKey, handshakeComplete, if (ch.isInitiator) Role.INIT else Role.RESP)
        )

        return handshakeComplete
    } // initChannel
} // class NoiseXXSecureChannel

private class NoiseIoHandshake(
    private val localKey: PrivKey,
    private val handshakeComplete: CompletableFuture<SecureChannel.Session>,
    private val role: Role
) : SimpleChannelInboundHandler<ByteBuf>() {
    private val handshakeState = HandshakeState(NoiseXXSecureChannel.protocolName, role.intVal)

    private val localNoiseState = Noise.createDH("25519")

    private var sentNoiseKeyPayload = false
    private var instancePayload: ByteArray? = null
    private var activated = false
    private var remotePeerId: PeerId? = null

    init {
        log.debug("Starting handshake")

        // configure the localDHState with the private
        // which will automatically generate the corresponding public key
        localNoiseState.setPrivateKey(NoiseXXSecureChannel.localStaticPrivateKey25519, 0)

        handshakeState.localKeyPair.copyFrom(localNoiseState)
        handshakeState.start()
    } // init

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (activated) return
        activated = true

        // even though both the alice and bob parties can have the payload ready
        // the Noise protocol only permits alice to send a packet first
        if (role == Role.INIT) {
            sendNoiseMessage(ctx)
        }
    } // channelActive

    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        channelActive(ctx)

        // we always read from the wire when it's the next action to take
        // capture any payloads
        if (handshakeState.action == HandshakeState.READ_MESSAGE) {
            readNoiseMessage(msg.toByteArray())
        }

        // verify the signature of the remote's noise static public key once
        // the remote public key has been provided by the XX protocol
        with(handshakeState.remotePublicKey) {
            if (hasPublicKey()) {
                remotePeerId = verifyPayload(ctx, instancePayload!!, this)
            }
        }

        // after reading messages and setting up state, write next message onto the wire
        if (handshakeState.action == HandshakeState.WRITE_MESSAGE) {
            sendNoiseStaticKeyAsPayload(ctx)
        }

        if (handshakeState.action == HandshakeState.SPLIT) {
            splitHandshake(ctx)
        }
    } // channelRead0

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        handshakeFailed(ctx, cause)
        ctx.channel().close()
    } // exceptionCaught

    override fun channelUnregistered(ctx: ChannelHandlerContext) {
        handshakeFailed(ctx, "Connection was closed ${ctx.channel()}")
        super.channelUnregistered(ctx)
    } // channelUnregistered

    private fun readNoiseMessage(msg: ByteArray) {
        log.debug("Noise handshake READ_MESSAGE")

        var payload = ByteArray(msg.size)
        var payloadLength = handshakeState.readMessage(msg, 0, msg.size, payload, 0)

        if (NoiseXXSecureChannel.rustInteroperability) {
            if (payloadLength > 0) {
                if (payloadLength < 2) throw SecureHandshakeError()
                payloadLength = payload.sliceArray(0..1).toUShortBigEndian()
                if (payload.size < payloadLength + 2) throw SecureHandshakeError()
                payload = payload.sliceArray(2 until payloadLength + 2)
            }
        }

        log.trace("msg.size:" + msg.size)
        log.trace("Read message size:$payloadLength")

        if (instancePayload == null && payloadLength > 0) {
            // currently, only allow storing a single payload for verification (this should maybe be changed to a queue)
            instancePayload = ByteArray(payloadLength)
            payload.copyInto(instancePayload!!, 0, 0, payloadLength)
        }
    } // readNoiseMessage

    /**
     * Sends the next Noise message with a payload of the identities and signatures
     * Currently does not include additional data in the payload.
     */
    private fun sendNoiseStaticKeyAsPayload(ctx: ChannelHandlerContext) {
        // only send the Noise static key once
        if (sentNoiseKeyPayload) return
        sentNoiseKeyPayload = true

        // the payload consists of the identity public key, and the signature of the noise static public key
        // the actual noise static public key is sent later as part of the XX handshake

        // get identity public key
        val identityPublicKey: ByteArray = marshalPublicKey(localKey.publicKey())

        // get noise static public key signature
        val localNoiseStaticKeySignature =
            localKey.sign(noiseSignaturePhrase(localNoiseState))

        // generate an appropriate protobuf element
        val noiseHandshakePayload =
            Spipe.NoiseHandshakePayload.newBuilder()
                .setLibp2PKey(ByteString.copyFrom(identityPublicKey))
                .setNoiseStaticKeySignature(ByteString.copyFrom(localNoiseStaticKeySignature))
                .setLibp2PData(ByteString.EMPTY)
                .setLibp2PDataSignature(ByteString.EMPTY)
                .build()
                .toByteArray()

        // create the message with the signed payload -
        // verification happens once the noise static key is shared
        log.debug("Sending signed Noise static public key as payload")
        sendNoiseMessage(ctx, noiseHandshakePayload)
    } // sendNoiseStaticKeyAsPayload

    private fun sendNoiseMessage(ctx: ChannelHandlerContext, msg: ByteArray? = null) {

        val lenMsg = if (!NoiseXXSecureChannel.rustInteroperability) {
            msg
        } else if (msg != null) {
            msg.size.uShortToBytesBigEndian() + msg
        } else {
            null
        }
        val msgLength = lenMsg?.size ?: 0

        val outputBuffer = ByteArray(msgLength + (2 * (handshakeState.localKeyPair.publicKeyLength + 16))) // 16 is MAC length
        val outputLength = handshakeState.writeMessage(outputBuffer, 0, lenMsg, 0, msgLength)

        log.debug("Noise handshake WRITE_MESSAGE")
        log.trace("Sent message length:$outputLength")

        ctx.writeAndFlush(outputBuffer.copyOfRange(0, outputLength).toByteBuf())
    } // sendNoiseMessage

    private fun verifyPayload(
        ctx: ChannelHandlerContext,
        payload: ByteArray,
        remotePublicKeyState: DHState
    ): PeerId {
        log.debug("Verifying noise static key payload")

        val (pubKeyFromMessage, signatureFromMessage) = unpackKeyAndSignature(payload)

        val verified = pubKeyFromMessage.verify(
            noiseSignaturePhrase(remotePublicKeyState),
            signatureFromMessage
        )

        log.debug("Remote verification is $verified")

        if (!verified) {
            handshakeFailed(ctx, InvalidRemotePubKey())
        }

        return PeerId.fromPubKey(pubKeyFromMessage)
    } // verifyPayload

    private fun unpackKeyAndSignature(payload: ByteArray): Pair<PubKey, ByteArray> {
        val noiseMsg = Spipe.NoiseHandshakePayload.parseFrom(payload)

        val publicKey = unmarshalPublicKey(noiseMsg.libp2PKey.toByteArray())
        val signature = noiseMsg.noiseStaticKeySignature.toByteArray()

        return Pair(publicKey, signature)
    } // unpackKeyAndSignature

    private fun splitHandshake(ctx: ChannelHandlerContext) {
        val cipherStatePair = handshakeState.split()

        val aliceSplit = cipherStatePair.sender
        val bobSplit = cipherStatePair.receiver
        log.debug("Split complete")

        // put alice and bob security sessions into the context and trigger the next action
        val secureSession = NoiseSecureChannelSession(
            PeerId.fromPubKey(localKey.publicKey()),
            remotePeerId!!,
            localKey.publicKey(),
            aliceSplit,
            bobSplit
        )

        handshakeSucceeded(ctx, secureSession)
    } // splitHandshake

    private fun handshakeSucceeded(ctx: ChannelHandlerContext, session: NoiseSecureChannelSession) {
        handshakeComplete.complete(session)
        ctx.pipeline()
            .addBefore(
                HandshakeNettyHandlerName,
                NoiseCodeNettyHandlerName,
                NoiseXXCodec(session.aliceCipher, session.bobCipher)
            )
        // according to Libp2p spec transport payload should also be length-prefixed
        // though Rust Libp2p implementation doesn't do it
        // https://github.com/libp2p/specs/tree/master/noise#wire-format
//        ctx.pipeline()
//            .addBefore(HandshakeNettyHandlerName, "NoiseXXPayloadLenCodec", UShortLengthCodec())
        ctx.pipeline().addAfter(
            NoiseCodeNettyHandlerName,
            "NoisePacketSplitter",
            SplitEncoder(MaxCipheredPacketLength - session.aliceCipher.macLength)
        )
        ctx.pipeline().remove(this)
        ctx.fireChannelActive()
    } // handshakeSucceeded

    private fun handshakeFailed(ctx: ChannelHandlerContext, cause: String) {
        handshakeFailed(ctx, Exception(cause))
    }
    private fun handshakeFailed(ctx: ChannelHandlerContext, cause: Throwable) {
        log.debug("Noise handshake failed", cause)

        handshakeComplete.completeExceptionally(cause)
        ctx.pipeline().remove(this)
    }
} // class NoiseIoHandshake

private fun noiseSignaturePhrase(dhState: DHState) =
    "noise-libp2p-static-key:".toByteArray() + dhState.publicKey

private val DHState.publicKey: ByteArray
    get() {
        val pubKey = ByteArray(publicKeyLength)
        getPublicKey(pubKey, 0)
        return pubKey
    }
