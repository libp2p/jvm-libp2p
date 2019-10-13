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
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.apache.logging.log4j.core.config.Configurator
import spipe.pb.Spipe
import java.util.Arrays
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class NoiseXXSecureChannel(private val localKey: PrivKey) :
        SecureChannel {

    companion object {
        const val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val announce = "/noise/$protocolName/0.1.0"

        @JvmStatic
        var localStaticPrivateKey25519: ByteArray = ByteArray(32)

        init {
            // initialize static Noise key
            Noise.random(localStaticPrivateKey25519)
        }
    }

    private var logger: Logger
    private var loggerNameParent: String = NoiseXXSecureChannel::class.java.name + this.hashCode()
    private lateinit var chid: String

    private lateinit var role: AtomicInteger

    private val handshakeHandlerName = "NoiseHandshake"

    override val announce = Companion.announce
    override val matcher = ProtocolMatcher(Mode.PREFIX, name = "/noise/$protocolName/0.1.0")

    init {
        logger = LogManager.getLogger(loggerNameParent)
        Configurator.setLevel(loggerNameParent, Level.DEBUG)
    }

    // simplified constructor
    fun initChannel(ch: P2PAbstractChannel): CompletableFuture<SecureChannel.Session> {
        return initChannel(ch, "")
    }

    override fun initChannel(
        ch: P2PAbstractChannel,
        selectedProtocol: String
    ): CompletableFuture<SecureChannel.Session> {
        role = if (ch.isInitiator) AtomicInteger(HandshakeState.INITIATOR) else AtomicInteger(HandshakeState.RESPONDER)

        chid = "ch:" + ch.nettyChannel.id().asShortText() + "-" + ch.nettyChannel.localAddress() + "-" + ch.nettyChannel.remoteAddress()
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
                        ctx.pipeline().remove(handshakeHandlerName + chid)
                        ctx.pipeline().remove(this)
                        logger.debug("Reporting secure channel failed")
                    }
                }
                ctx.fireUserEventTriggered(evt)
            }
        }
        ch.nettyChannel.pipeline().addLast(handshakeHandlerName + chid, NoiseIoHandshake())
        ch.nettyChannel.pipeline().addLast(handshakeHandlerName + chid + "ResultHandler", resultHandler)
        return ret
    }

    inner class NoiseIoHandshake() : SimpleChannelInboundHandler<ByteBuf>() {

        private val handshakestate: HandshakeState = HandshakeState(protocolName, role.get())
        private var loggerName: String
        private var logger2: Logger

        private var localNoiseState: DHState
        private var sentNoiseKeyPayload = false

        private val instancePayload = ByteArray(65535)
        private var instancePayloadLength = 0

        init {
            val roleString = if (role.get() == 1) "INIT" else "RESP"
//            loggerName = loggerNameParent + "." + this.hashCode().toString().substring(5)
            loggerName = "|" + roleString + "|" + chid + "." + loggerNameParent
            loggerName = loggerName.replace(".", "_")
//            System.out.println("loggerName:"+loggerName)

            logger2 = LogManager.getLogger(loggerName)
            Configurator.setLevel(loggerName, Level.DEBUG)

            logger2.debug("Starting handshake")

            // configure the localDHState with the private
            // which will automatically generate the corresponding public key
            localNoiseState = Noise.createDH("25519")
            localNoiseState.setPrivateKey(localStaticPrivateKey25519, 0)
            handshakestate.localKeyPair.copyFrom(localNoiseState)
            handshakestate.start()
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg1: ByteBuf) {
            logger2.debug("Starting channelRead0")
            val msg = msg1.toByteArray()

            channelActive(ctx)

            if (role.get() == HandshakeState.RESPONDER && flagRemoteVerified && !flagRemoteVerifiedPassed) {
                logger2.error("Responder verification of Remote peer id has failed")
                throw Exception("Responder verification of Remote peer id has failed")
            }

            // we always read from the wire when it's the next action to take
            // capture any payloads
            val payload = ByteArray(65535)
            var payloadLength = 0
            if (handshakestate.action == HandshakeState.READ_MESSAGE) {
                payloadLength = handshakestate.readMessage(msg, 0, msg.size, payload, 0)
            }

            val remotePublicKeyState: DHState = handshakestate.remotePublicKey
            val remotePublicKey = ByteArray(remotePublicKeyState.publicKeyLength)
            remotePublicKeyState.getPublicKey(remotePublicKey, 0)

            if (payloadLength > 0 && instancePayloadLength == 0) {
                // currently, only allow storing a single payload for verification (this should maybe be changed to a queue)
                payload.copyInto(instancePayload, 0, 0, payloadLength)
                instancePayloadLength = payloadLength
            }

            if (!Arrays.equals(remotePublicKey, ByteArray(remotePublicKeyState.publicKeyLength))) {
                verifyPayload(instancePayload, instancePayloadLength, remotePublicKey)
            }

            // after reading messages and setting up state, write next message onto the wire
            if (role.get() == HandshakeState.RESPONDER && handshakestate.action == HandshakeState.WRITE_MESSAGE) {
                logger2.debug("Sending responder noise key payload")
                sendHandshakePayload(ctx)
            } else if (handshakestate.action == HandshakeState.WRITE_MESSAGE) {
                val sndmessage = ByteArray(65535)
                val sndmessageLength: Int
                sndmessageLength = handshakestate.writeMessage(sndmessage, 0, null, 0, 0)
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
                ctx.fireChannelActive()
                ctx.channel().pipeline().remove(this)
                return
            }
        }

        override fun channelRegistered(ctx: ChannelHandlerContext) {
            if (activated) {
                return
            }
            activated = true

            // even though both the alice and bob parties can have the payload ready
            // the Noise protocol only permits alice to send a packet first
            if (role.get() == HandshakeState.INITIATOR) {
                logger2.debug("Sending initiator noise key payload")
                sendHandshakePayload(ctx)
            }
            logger2.debug("Noise registration complete")
        }

        /**
         * Sends the next Noise message with a payload of the identities and signatures
         * Currently does not include additional data in the payload.
         */
        private fun sendHandshakePayload(ctx: ChannelHandlerContext) {
            if (sentNoiseKeyPayload) return
            sentNoiseKeyPayload = true
            // put signed Noise public key into message
            val localNoisePubKey = ByteArray(localNoiseState.publicKeyLength)
            localNoiseState.getPublicKey(localNoisePubKey, 0)

            val localNoiseStaticKeySignature = localKey.sign("noise-libp2p-static-key:".toByteArray() + localNoisePubKey)

            // generate an appropriate protobuf element
            val identityPublicKey: ByteArray = marshalPublicKey(localKey.publicKey())
            val noiseHandshakePayload =
                    Spipe.NoiseHandshakePayload.newBuilder()
                            .setLibp2PKey(ByteString.copyFrom(identityPublicKey))
                            .setNoiseStaticKeySignature(ByteString.copyFrom(localNoiseStaticKeySignature))
                            .setLibp2PData(ByteString.EMPTY)
                            .setLibp2PDataSignature(ByteString.EMPTY)
                            .build()

            // create the message with the signed payload - verification happens once the noise static key is shared
            val msgBuffer = ByteArray(65535)
            val msgLength = handshakestate.writeMessage(
                    msgBuffer,
                    0,
                    noiseHandshakePayload.toByteArray(),
                    0,
                    noiseHandshakePayload.toByteArray().size
            )

            // put the message frame which also contains the payload onto the wire
            ctx.writeAndFlush(msgBuffer.copyOfRange(0, msgLength).toByteBuf())
        }

        private fun verifyPayload(
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

            flagRemoteVerifiedPassed = remotePubKeyFromMessage.verify("noise-libp2p-static-key:".toByteArray() + remotePublicKey, remoteSignatureFromMessage)

            if (flagRemoteVerifiedPassed) {
                logger2.debug("Remote verification passed")
            } else {
                logger2.error("Remote verification failed")
                throw Exception("Responder verification of Remote peer id has failed")
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
