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
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.config.Configurator
import spipe.pb.Spipe
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

open class NoiseXXSecureChannel(val localKey: PrivKey, val privateKey25519: ByteArray) :
    SecureChannel {

    private val logger = LogManager.getLogger(NoiseXXSecureChannel::class.java.name)

    private lateinit var role: AtomicInteger
    private lateinit var localDHState: DHState

    private val handshakeHandlerName = "NoiseHandshake"

    companion object {
        const val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"
        const val announce = "/noise/$protocolName/0.1.0"
    }

    override val announce = Companion.announce
    override val matcher = ProtocolMatcher(Mode.PREFIX, name = "/noise/$protocolName/0.1.0")

    init {
        Configurator.setLevel(NoiseXXSecureChannel::class.java.name, Level.DEBUG)
    }

    fun initChannel(ch: P2PAbstractChannel): CompletableFuture<SecureChannel.Session> {
        return initChannel(ch, "")
    }

    override fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<SecureChannel.Session> {
        role = if (ch.isInitiator) AtomicInteger(HandshakeState.INITIATOR) else AtomicInteger(HandshakeState.RESPONDER)

        // configure the localDHState with the private
        // which will automatically generate the corresponding public key
        localDHState = Noise.createDH("25519")
        localDHState.setPrivateKey(privateKey25519, 0)

        val ret = CompletableFuture<SecureChannel.Session>()
        val resultHandler = object : ChannelInboundHandlerAdapter() {
            override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                when (evt) {
                    is SecureChannelInitialized -> {

                        ctx.channel().attr(SECURE_SESSION).set(evt.session)

                        ctx.pipeline().remove(handshakeHandlerName)
                        ctx.pipeline().remove(this)

                        ctx.pipeline().addFirst(object : SimpleChannelInboundHandler<ByteBuf>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
                                val get: NoiseSecureChannelSession = ctx.channel().attr(SECURE_SESSION).get() as NoiseSecureChannelSession
                                var additionalData = ByteArray(65535)
                                var plainText = ByteArray(65535)
                                var cipherText = msg.toByteArray()
                                var length = msg.getShort(0).toInt()
                                logger.debug("decrypt length:" + length)
                                var l = get.bobCipher.decryptWithAd(additionalData, cipherText, 2, plainText, 0, length)
                                var rec2 = plainText.copyOf(l).toString(StandardCharsets.UTF_8)
                                ctx.pipeline().fireChannelRead(rec2)
                            }
                        })
                        ctx.pipeline().addFirst(object: ChannelOutboundHandlerAdapter() {
                            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                                msg as ByteBuf
                                val get: NoiseSecureChannelSession = ctx.channel().attr(SECURE_SESSION).get() as NoiseSecureChannelSession
                                var additionalData = ByteArray(65535)
                                var cipherText = ByteArray(65535)
                                var plaintext = msg.toByteArray()
                                var length = get.aliceCipher.encryptWithAd(additionalData, plaintext, 0, cipherText, 2, plaintext.size)
                                logger.debug("encrypt length:" + length)
                                ctx.write(Arrays.copyOf(cipherText, length + 2).toByteBuf().setShort(0, length))
                                logger.debug("channel outbound handler write: "+msg)
                            }
                        })

                        ret.complete(evt.session)

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
            }
        }
        ch.nettyChannel.pipeline().addLast(handshakeHandlerName, NoiseIoHandshake())
        ch.nettyChannel.pipeline().addLast(handshakeHandlerName + "ResultHandler", resultHandler)
        return ret
    }

    inner class NoiseIoHandshake : SimpleChannelInboundHandler<ByteBuf>() {
        private val handshakestate: HandshakeState = HandshakeState(protocolName, role.get())

        init {
            handshakestate.localKeyPair.copyFrom(localDHState)
            handshakestate.start()
            logger.debug("Starting handshake")
        }

        override fun channelRead0(ctx: ChannelHandlerContext, msg1: ByteBuf) {
            logger.debug("Starting channelRead0")
            val msg = msg1.array()

            channelActive(ctx)

            if (role.get() == HandshakeState.RESPONDER && flagRemoteVerified && !flagRemoteVerifiedPassed) {
                logger.error("Responder verification of Remote peer id has failed")
                throw Exception("Responder verification of Remote peer id has failed")
            }

            // if we are here, we are still in handshake setup phase

            // we always read from the wire when it's the next action to take
            val payload = ByteArray(65535)
            var payloadLength = 0
            if (handshakestate.action == HandshakeState.READ_MESSAGE) {
                payloadLength = handshakestate.readMessage(msg, 0, msg.size, payload, 0)
            }

            if (role.get() == HandshakeState.RESPONDER && !flagRemoteVerified) {
                // the self-signed remote pubkey and signature would be retrieved from the first Noise payload
                val inp = Spipe.Exchange.parseFrom(payload.copyOfRange(0, payloadLength))
                // validate the signature
                val inpub = unmarshalPublicKey(inp.epubkey.toByteArray())
                val verification = inpub.verify(inp.epubkey.toByteArray(), inp.signature.toByteArray())

                flagRemoteVerified = true
                if (verification) {
                    logger.debug("Remote verification passed")
                    flagRemoteVerifiedPassed = true
                } else {
                    logger.error("Remote verification failed")
                    flagRemoteVerifiedPassed = false // being explicit about it
                    throw Exception("Responder verification of Remote peer id has failed")
                    // throwing exception for early exit of protocol and for application to handle
                }
            }

            // after reading messages and setting up state, write next message onto the wire
            if (handshakestate.action == HandshakeState.WRITE_MESSAGE) {
                val sndmessage = ByteArray(65535)
                val sndmessageLength: Int
                sndmessageLength = handshakestate.writeMessage(sndmessage, 0, null, 0, 0)
                ctx.writeAndFlush(sndmessage.copyOfRange(0, sndmessageLength).toByteBuf())
            }

            if (handshakestate.action == HandshakeState.SPLIT) {
                cipherStatePair = handshakestate.split()
                aliceSplit = cipherStatePair.sender
                bobSplit = cipherStatePair.receiver
                logger.debug("Split complete")

                // put alice and bob security sessions into the context and trigger the next action
                val secureChannelInitialized = SecureChannelInitialized(
                    NoiseSecureChannelSession(
                        PeerId.fromPubKey(localKey.publicKey()),
                        PeerId.random(),
                        localKey.publicKey(),
                        aliceSplit,
                        bobSplit
                    ) as SecureChannel.Session)
                ctx.fireUserEventTriggered(secureChannelInitialized)
                return
            }
        }

        override fun channelRegistered(ctx: ChannelHandlerContext) {
            if (activated) {
                return
            }
            logger.debug("Registration starting")
            activated = true

            if (role.get() == HandshakeState.INITIATOR) {
                val msgBuffer = ByteArray(65535)

                // TODO : include data fields into protobuf struct to match spec
                // alice needs to put signed peer id public key into message
                val signed = localKey.sign(localKey.publicKey().bytes())

                // generate an appropriate protobuf element
                val bs = Spipe.Exchange.newBuilder().setEpubkey(ByteString.copyFrom(localKey.publicKey().bytes()))
                    .setSignature(ByteString.copyFrom(signed)).build()

                // create the message
                // also create assign the signed payload
                val msgLength = handshakestate.writeMessage(msgBuffer, 0, bs.toByteArray(), 0, bs.toByteArray().size)

                // put the message frame which also contains the payload onto the wire
                ctx.writeAndFlush(msgBuffer.copyOfRange(0, msgLength).toByteBuf())
            }
            logger.debug("Registration complete")
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            logger.debug("Activation starting")
            channelRegistered(ctx)
            logger.debug("Activation complete")
        }

        private var activated = false
        private var flagRemoteVerified = false
        private var flagRemoteVerifiedPassed = false
        private lateinit var aliceSplit: CipherState
        private lateinit var bobSplit: CipherState
        private lateinit var cipherStatePair: CipherStatePair
    }
}
