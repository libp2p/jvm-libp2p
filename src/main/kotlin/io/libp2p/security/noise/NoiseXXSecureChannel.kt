package io.libp2p.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import org.apache.logging.log4j.LogManager
import spipe.pb.Spipe
import java.util.Arrays
import java.util.concurrent.CompletableFuture

private enum class Role(val intVal: Int) { INIT(HandshakeState.INITIATOR), RESP(HandshakeState.RESPONDER) }

private val log = LogManager.getLogger(NoiseXXSecureChannel::class.java)

class NoiseXXSecureChannel(private val localKey: PrivKey) :
    SecureChannel {

    companion object {
        const val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val announce = "/noise/$protocolName/0.1.0"

        @JvmStatic
        var localStaticPrivateKey25519: ByteArray = ByteArray(32).also { Noise.random(it) }
    }

    private val handshakeHandlerName = "NoiseHandshake"

    override val announce = Companion.announce
    override val matcher = ProtocolMatcher(Mode.PREFIX, name = "/noise/$protocolName/0.1.0")

    // simplified constructor
    fun initChannel(ch: P2PChannel): CompletableFuture<SecureChannel.Session> {
        return initChannel(ch, "")
    }

    override fun initChannel(
        ch: P2PChannel,
        selectedProtocol: String
    ): CompletableFuture<SecureChannel.Session> {
        val handshakeComplete = CompletableFuture<SecureChannel.Session>()

        ch.pushHandler(
            handshakeHandlerName,
            NoiseIoHandshake(localKey, handshakeComplete, if (ch.isInitiator) Role.INIT else Role.RESP)
        )

        return handshakeComplete
    }

}

private class NoiseIoHandshake(
    private val localKey: PrivKey,
    private val handshakeComplete: CompletableFuture<SecureChannel.Session>,
    private val role: Role
) : SimpleChannelInboundHandler<ByteBuf>() {
    private val handshakestate = HandshakeState(NoiseXXSecureChannel.protocolName, role.intVal)

    private var localNoiseState: DHState
    private var sentNoiseKeyPayload = false

    private lateinit var instancePayload: ByteArray
    private var instancePayloadLength = 0


    private var activated = false
    private lateinit var aliceSplit: CipherState
    private lateinit var bobSplit: CipherState
    private lateinit var cipherStatePair: CipherStatePair

    init {
        log.debug("Starting handshake")

        // configure the localDHState with the private
        // which will automatically generate the corresponding public key
        localNoiseState = Noise.createDH("25519")
        localNoiseState.setPrivateKey(NoiseXXSecureChannel.localStaticPrivateKey25519, 0)
        handshakestate.localKeyPair.copyFrom(localNoiseState)
        handshakestate.start()
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (activated) return
        activated = true

        // even though both the alice and bob parties can have the payload ready
        // the Noise protocol only permits alice to send a packet first
        if (role == Role.INIT) {
            sendNoiseStaticKeyAsPayload(ctx)
        }
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg1: ByteBuf) {
        val msg = msg1.toByteArray()

        channelActive(ctx)

        // we always read from the wire when it's the next action to take
        // capture any payloads
        val payload = ByteArray(msg.size)
        var payloadLength = 0
        if (handshakestate.action == HandshakeState.READ_MESSAGE) {
            log.debug("Noise handshake READ_MESSAGE")
            try {
                payloadLength = handshakestate.readMessage(msg, 0, msg.size, payload, 0)
                log.trace("msg.size:" + msg.size)
                log.trace("Read message size:$payloadLength")
            } catch (e: Exception) {
                handshakeFailed(ctx, e)
                return
            }
        }

        val remotePublicKeyState: DHState = handshakestate.remotePublicKey
        val remotePublicKey = ByteArray(remotePublicKeyState.publicKeyLength)
        remotePublicKeyState.getPublicKey(remotePublicKey, 0)

        if (payloadLength > 0 && instancePayloadLength == 0) {
            // currently, only allow storing a single payload for verification (this should maybe be changed to a queue)
            instancePayload = ByteArray(payloadLength)
            payload.copyInto(instancePayload, 0, 0, payloadLength)
            instancePayloadLength = payloadLength
        }

        // verify the signature of the remote's noise static public key once the remote public key has been provided by the XX protocol
        if (!Arrays.equals(remotePublicKey, ByteArray(remotePublicKeyState.publicKeyLength))) {
            verifyPayload(ctx, instancePayload, instancePayloadLength, remotePublicKey)
        }

        // after reading messages and setting up state, write next message onto the wire
        if (handshakestate.action == HandshakeState.WRITE_MESSAGE) {
            if (role == Role.RESP) {
                sendNoiseStaticKeyAsPayload(ctx)
            } else {
                val sndmessage = ByteArray(2 * handshakestate.localKeyPair.publicKeyLength)
                val sndmessageLength = handshakestate.writeMessage(sndmessage, 0, null, 0, 0)

                log.debug("Noise handshake WRITE_MESSAGE")
                log.trace("Sent message length:$sndmessageLength")

                ctx.writeAndFlush(sndmessage.copyOfRange(0, sndmessageLength).toByteBuf())
            }
        }

        if (handshakestate.action == HandshakeState.SPLIT) {
            cipherStatePair = handshakestate.split()
            aliceSplit = cipherStatePair.sender
            bobSplit = cipherStatePair.receiver
            log.debug("Split complete")

            // put alice and bob security sessions into the context and trigger the next action
            val secureSession = NoiseSecureChannelSession(
                PeerId.fromPubKey(localKey.publicKey()),
                PeerId.random(),
                localKey.publicKey(),
                aliceSplit,
                bobSplit
            )

            handshakeSucceeded(ctx, secureSession)
        }
    }

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
        val localNoisePubKey = ByteArray(localNoiseState.publicKeyLength)
        localNoiseState.getPublicKey(localNoisePubKey, 0)
        val localNoiseStaticKeySignature =
            localKey.sign("noise-libp2p-static-key:".toByteArray() + localNoisePubKey)

        // generate an appropriate protobuf element
        val noiseHandshakePayload =
            Spipe.NoiseHandshakePayload.newBuilder()
                .setLibp2PKey(ByteString.copyFrom(identityPublicKey))
                .setNoiseStaticKeySignature(ByteString.copyFrom(localNoiseStaticKeySignature))
                .setLibp2PData(ByteString.EMPTY)
                .setLibp2PDataSignature(ByteString.EMPTY)
                .build()

        // create the message with the signed payload - verification happens once the noise static key is shared
        val msgBuffer =
            ByteArray(noiseHandshakePayload.toByteArray().size + (2 * (handshakestate.localKeyPair.publicKeyLength + 16))) // mac length is 16
        val msgLength = handshakestate.writeMessage(
            msgBuffer,
            0,
            noiseHandshakePayload.toByteArray(),
            0,
            noiseHandshakePayload.toByteArray().size
        )
        log.debug("Sending signed Noise static public key as payload")
        log.debug("Noise handshake WRITE_MESSAGE")
        log.trace("Sent message size:$msgLength")
        // put the message frame which also contains the payload onto the wire
        ctx.writeAndFlush(msgBuffer.copyOfRange(0, msgLength).toByteBuf())
    }

    private fun verifyPayload(
        ctx: ChannelHandlerContext,
        payload: ByteArray,
        payloadLength: Int,
        remotePublicKey: ByteArray
    ) {
        log.debug("Verifying noise static key payload")

        // the self-signed remote pubkey and signature would be retrieved from the first Noise payload
        val inp = Spipe.NoiseHandshakePayload.parseFrom(payload.copyOfRange(0, payloadLength))
        // validate the signature
        val data: ByteArray = inp.libp2PKey.toByteArray()
        val remotePubKeyFromMessage = unmarshalPublicKey(data)
        val remoteSignatureFromMessage = inp.noiseStaticKeySignature.toByteArray()

        val flagRemoteVerifiedPassed = remotePubKeyFromMessage.verify(
            "noise-libp2p-static-key:".toByteArray() + remotePublicKey,
            remoteSignatureFromMessage
        )

        if (flagRemoteVerifiedPassed) {
            log.debug("Remote verification passed")
        } else {
            handshakeFailed(ctx, "Responder verification of Remote peer id has failed")
        }
    }

    private fun handshakeSucceeded(ctx: ChannelHandlerContext, session: NoiseSecureChannelSession) {
        handshakeComplete.complete(session)
        ctx.pipeline().remove(this)
        ctx.pipeline().addLast(NoiseXXCodec(session.aliceCipher, session.bobCipher))
        ctx.fireChannelActive()
    } // handshakeSucceeded

    private fun handshakeFailed(ctx: ChannelHandlerContext, cause: String) {
        handshakeFailed(ctx, Exception(cause))
    }
    private fun handshakeFailed(ctx: ChannelHandlerContext, cause: Throwable) {
        log.error(cause.message)

        handshakeComplete.completeExceptionally(cause)
        ctx.pipeline().remove(this)
    }
}
