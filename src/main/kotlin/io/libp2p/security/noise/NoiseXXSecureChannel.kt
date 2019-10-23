package io.libp2p.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multistream.Mode
import io.libp2p.core.multistream.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.events.SecureChannelFailed
import io.libp2p.etc.events.SecureChannelInitialized
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.SimpleChannelInboundHandler
import org.apache.logging.log4j.LogManager
import spipe.pb.Spipe
import java.util.Arrays
import java.util.concurrent.CompletableFuture

class NoiseXXSecureChannel(private val localKey: PrivKey) :
    SecureChannel {

    private enum class Role(val intVal: Int) { INIT(HandshakeState.INITIATOR), RESP(HandshakeState.RESPONDER) }

    companion object {
        const val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val announce = "/noise/$protocolName/0.1.0"

        @JvmStatic
        var localStaticPrivateKey25519: ByteArray = ByteArray(32).also { Noise.random(it) }
    }

    private val loggerNameParent = NoiseXXSecureChannel::class.java.name + '.' + this.hashCode()
    private val logger = LogManager.getLogger(loggerNameParent)
    private lateinit var chid: String

    private lateinit var role: Role

    private val handshakeHandlerName = "NoiseHandshake"

    override val announce = Companion.announce
    override val matcher = ProtocolMatcher(Mode.PREFIX, name = "/noise/$protocolName/0.1.0")

    override fun initChannel(
        ch: P2PAbstractChannel,
        selectedProtocol: String
    ): CompletableFuture<SecureChannel.Session> {
        role = if (ch.isInitiator) Role.INIT else Role.RESP

        chid =
            "ch=" + ch.nettyChannel.id().asShortText() + "-" + ch.nettyChannel.localAddress() + "-" + ch.nettyChannel.remoteAddress()
        logger.debug(chid)

        val ret = CompletableFuture<SecureChannel.Session>()
        val resultHandler = object : ChannelInboundHandlerAdapter() {
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                when (evt) {
                    is SecureChannelInitialized -> {
                        val session = evt.session as NoiseSecureChannelSession
                        ctx.channel().attr(SECURE_SESSION).set(session)

                        ret.complete(session)
                        ctx.pipeline().remove(this)
                        ctx.pipeline().addLast(NoiseXXCodec(session.aliceCipher, session.bobCipher))

                        logger.debug("Reporting secure channel initialized")
                    }
                    is SecureChannelFailed -> {
                        ret.completeExceptionally(evt.exception)

                        ctx.pipeline().remove(handshakeHandlerName)
                        ctx.pipeline().remove(this)

                        logger.debug("Reporting secure channel failed")
                    }
                }
                ctx.fireUserEventTriggered(evt)
                ctx.fireChannelActive()
            }
        }
        ch.nettyChannel.pipeline().addLast(handshakeHandlerName, NoiseIoHandshake())
        ch.nettyChannel.pipeline().addLast(handshakeHandlerName + "ResultHandler", resultHandler)
        return ret
    }

    inner class NoiseIoHandshake() : SimpleChannelInboundHandler<ByteBuf>() {

        private val handshakestate: HandshakeState = HandshakeState(protocolName, role.intVal)
        private val logger2 = LogManager.getLogger("$loggerNameParent.$chid.$role")

        private var localNoiseState: DHState
        private var sentNoiseKeyPayload = false

        private lateinit var instancePayload: ByteArray
        private var instancePayloadLength = 0

        init {
            logger2.debug("Starting handshake")

            // configure the localDHState with the private
            // which will automatically generate the corresponding public key
            localNoiseState = Noise.createDH("25519")
            localNoiseState.setPrivateKey(localStaticPrivateKey25519, 0)
            handshakestate.localKeyPair.copyFrom(localNoiseState)
            handshakestate.start()
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg1: ByteBuf) {
            val msg = msg1.toByteArray()

            channelActive(ctx)

            if (role == Role.RESP && flagRemoteVerified && !flagRemoteVerifiedPassed) {
                logger2.error("Responder verification of Remote peer id has failed")
                ctx.fireUserEventTriggered(SecureChannelFailed(Exception("Responder verification of Remote peer id has failed")))
                return
            }

            // we always read from the wire when it's the next action to take
            // capture any payloads
            val payload = ByteArray(msg.size)
            var payloadLength = 0
            if (handshakestate.action == HandshakeState.READ_MESSAGE) {
                logger2.debug("Noise handshake READ_MESSAGE")
                try {
                    payloadLength = handshakestate.readMessage(msg, 0, msg.size, payload, 0)
                    logger2.trace("msg.size:" + msg.size)
                    logger2.trace("Read message size:$payloadLength")
                } catch (e: Exception) {
                    logger2.debug("Exception e:" + e.toString())
                    ctx.fireUserEventTriggered(SecureChannelFailed(e))
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
            if (role == Role.RESP && handshakestate.action == HandshakeState.WRITE_MESSAGE) {
                sendNoiseStaticKeyAsPayload(ctx)
            } else if (handshakestate.action == HandshakeState.WRITE_MESSAGE) {
                val sndmessage = ByteArray(2 * handshakestate.localKeyPair.publicKeyLength)
                val sndmessageLength: Int
                logger2.debug("Noise handshake WRITE_MESSAGE")
                sndmessageLength = handshakestate.writeMessage(sndmessage, 0, null, 0, 0)
                logger2.trace("Sent message length:$sndmessageLength")
                ctx.writeAndFlush(sndmessage.copyOfRange(0, sndmessageLength).toByteBuf())
            }

            if (handshakestate.action == HandshakeState.SPLIT && flagRemoteVerifiedPassed) {
                cipherStatePair = handshakestate.split()
                aliceSplit = cipherStatePair.sender
                bobSplit = cipherStatePair.receiver
                logger2.debug("Split complete")

                // put alice and bob security sessions into the context and trigger the next action
                val secureChannelInitialized = SecureChannelInitialized(
                    NoiseSecureChannelSession(
                        PeerId.fromPubKey(localKey.publicKey()),
                        PeerId.random(),
                        localKey.publicKey(),
                        aliceSplit,
                        bobSplit
                    ) as SecureChannel.Session
                )
                ctx.fireUserEventTriggered(secureChannelInitialized)
//                ctx.fireChannelActive()
                ctx.channel().pipeline().remove(this)
            }
        }

        override fun channelRegistered(ctx: ChannelHandlerContext) {
            if (activated) return
            activated = true

            // even though both the alice and bob parties can have the payload ready
            // the Noise protocol only permits alice to send a packet first
            if (role == Role.INIT) {
                sendNoiseStaticKeyAsPayload(ctx)
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
            logger2.debug("Sending signed Noise static public key as payload")
            logger2.debug("Noise handshake WRITE_MESSAGE")
            logger2.trace("Sent message size:$msgLength")
            // put the message frame which also contains the payload onto the wire
            ctx.writeAndFlush(msgBuffer.copyOfRange(0, msgLength).toByteBuf())
        }

        private fun verifyPayload(
            ctx: ChannelHandlerContext,
            payload: ByteArray,
            payloadLength: Int,
            remotePublicKey: ByteArray
        ) {
            logger2.debug("Verifying noise static key payload")
            flagRemoteVerified = true

            // the self-signed remote pubkey and signature would be retrieved from the first Noise payload
            val inp = Spipe.NoiseHandshakePayload.parseFrom(payload.copyOfRange(0, payloadLength))
            // validate the signature
            val data: ByteArray = inp.libp2PKey.toByteArray()
            val remotePubKeyFromMessage = unmarshalPublicKey(data)
            val remoteSignatureFromMessage = inp.noiseStaticKeySignature.toByteArray()

            flagRemoteVerifiedPassed = remotePubKeyFromMessage.verify(
                "noise-libp2p-static-key:".toByteArray() + remotePublicKey,
                remoteSignatureFromMessage
            )

            if (flagRemoteVerifiedPassed) {
                logger2.debug("Remote verification passed")
            } else {
                logger2.error("Remote verification failed")
                ctx.fireUserEventTriggered(SecureChannelFailed(Exception("Responder verification of Remote peer id has failed")))
                return
//                ctx.fireChannelActive()
                // throwing exception for early exit of protocol and for application to handle
            }
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            channelRegistered(ctx)
        }

        private var activated = false
        private var flagRemoteVerified = false
        private var flagRemoteVerifiedPassed = false
        private lateinit var aliceSplit: CipherState
        private lateinit var bobSplit: CipherState
        private lateinit var cipherStatePair: CipherStatePair
    }
}
