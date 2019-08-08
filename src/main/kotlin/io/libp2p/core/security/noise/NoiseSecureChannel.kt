package io.libp2p.core.security.noise

import com.google.protobuf.ByteString
import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.DHState
import com.southernstorm.noise.protocol.HandshakeState
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.ProtocolBindingInitializer
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.util.replace
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import org.apache.logging.log4j.LogManager
import spipe.pb.Spipe
import java.util.concurrent.CompletableFuture

class NoiseSecureChannel(val localKey: PrivKey, val localDHState: DHState, val remoteDHState: DHState, val role: Int) :
    SecureChannel {
    private val log = LogManager.getLogger(NoiseSecureChannel::class.java)

    private val HandshakeHandlerName = "NoiseHandshake"

    override val announce = "/noise/Noise_XX_25519_ChaChaPoly_SHA256/0.1.0"
    override val matcher = ProtocolMatcher(Mode.PREFIX, name = "/noise/Noise_XX_25519_ChaChaPoly_SHA256/0.1.0")
    // see if there is a lighter-weight matcher interface that can be fed a filter function
    // the announce and matcher values must be more flexible in order to be able to respond appropriately
    // currently, these are fixed string values, which isn't compatible with dynamic announcements handling

    override fun initializer(): ProtocolBindingInitializer<SecureChannel.Session> {
        val ret = CompletableFuture<SecureChannel.Session>()

        return ProtocolBindingInitializer(
            object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().replace(this,
                        listOf(HandshakeHandlerName to NoiseIoHandshake()))
                }
            }, ret
        )
    }

    inner class NoiseIoHandshake() : ChannelInboundHandlerAdapter() {
        private var handshakestate: HandshakeState? = null
        private var activated = false

        init {
            handshakestate = HandshakeState("Noise_IX_25519_ChaChaPoly_SHA256", role)

            // create the static key
//                handshakestate!!.localKeyPair.generateKeyPair()

            handshakestate!!.localKeyPair.copyFrom(localDHState)

            handshakestate!!.start()

            println("handshake started:" + role)
        }

        override fun channelRegistered(ctx: ChannelHandlerContext?) {
            super.channelRegistered(ctx)

            if (role == HandshakeState.INITIATOR) {
                val msgBuffer = ByteArray(65535)

                // alice needs to put signed peer id public key into message
                val signed = localKey.sign(localKey.publicKey().bytes())

                // generate an appropriate protobuf element
                val bs = Spipe.Exchange.newBuilder().setEpubkey(ByteString.copyFrom(localKey.publicKey().bytes()))
                    .setSignature(ByteString.copyFrom(signed)).build()

                // create the message
                // also create assign the signed payload
                val msgLength = handshakestate!!.writeMessage(msgBuffer, 0, bs.toByteArray(), 0, bs.toByteArray().size)

                // put the message frame which also contains the payload onto the wire
                ctx?.writeAndFlush(msgBuffer.copyOfRange(0, msgLength))
            }

            if (role == HandshakeState.RESPONDER) {
                // a responder has no read or write actions to take
                // upon instantiation/registration
            }

            println("registered chan")
        }

        private var flagRemoteVerified = false
        private var flagRemoteVerifiedPassed = false
        private var aliceSplit: CipherState? = null
        private var bobSplit: CipherState? = null
        private var cipherStatePair: CipherStatePair? = null

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            println("start.handshakestate.action:" + handshakestate?.action)

            if (role == HandshakeState.RESPONDER && flagRemoteVerified && !flagRemoteVerifiedPassed) {
                throw Exception("Responder verification of Remote peer id has failed.")
            }

            // we always read from the wire when it's the next action to take
            val m = msg as ByteArray
            println("msg length:" + msg.size)
            println("channelRead:" + msg.asList())

            val payload = ByteArray(65535)
            var payloadLength = 0

            if (handshakestate?.action == HandshakeState.READ_MESSAGE) {
                payloadLength = handshakestate!!.readMessage(msg, 0, msg.size, payload, 0)
            }

            if (role == HandshakeState.RESPONDER && !flagRemoteVerified) {
                // the self-signed remote pubkey and signature would be retrieved from the first Noise payload
                val inp = Spipe.Exchange.parseFrom(payload.copyOfRange(0, payloadLength))
                // validate the signature
                val inpub = unmarshalPublicKey(inp.epubkey.toByteArray())
                val verification = inpub.verify(inp.epubkey.toByteArray(), inp.signature.toByteArray())

                flagRemoteVerified = true
                if (verification) {
                    println("remote verification passed")
                    flagRemoteVerifiedPassed = true
                } else {
                    println("remote verification failed")
                    flagRemoteVerifiedPassed = false // being explicit about it
                    throw Exception("Responder verification of Remote peer id has failed.")
                }

            }

            // after reading messages and setting up state, write next message onto the wire
            if (handshakestate!!.action == HandshakeState.WRITE_MESSAGE) {
                val sndmessage = ByteArray(65535)
                var sndmessageLength = 0
                sndmessageLength = handshakestate!!.writeMessage(sndmessage, 0, null, 0, 0)
                ctx.writeAndFlush(sndmessage.copyOfRange(0, sndmessageLength))
            }

            if (handshakestate?.action == HandshakeState.SPLIT) {
                cipherStatePair = handshakestate?.split()
                aliceSplit = cipherStatePair?.sender
                bobSplit = cipherStatePair?.receiver
                println("split complete.."+role)

                if (role == HandshakeState.INITIATOR) {
                    println("writing starting message..")
                    var encryptedMessage = ByteArray(65535)
                    var encryptedMessageLength = 0
                    val s1 = "Hello World"
                    encryptedMessageLength = cipherStatePair?.sender?.encryptWithAd(null, s1.toByteArray(), 0, encryptedMessage, 0, s1.toByteArray().size)!!
                    ctx.writeAndFlush(encryptedMessage.copyOfRange(0,encryptedMessageLength))
                }
                return

            }


            // once handshake is complete
            // use appropriate chaining key for encypting/decrypting transport messages
            // initiator and responder should have a shared symmetric key for transport messages
            if (handshakestate?.action == HandshakeState.COMPLETE && role == HandshakeState.RESPONDER) {
                var decryptedMessage = ByteArray(65535)
                var decryptedMessageLength = 0
                decryptedMessageLength = cipherStatePair?.receiver?.decryptWithAd(null, msg as ByteArray, 0, decryptedMessage, 0, (msg as ByteArray).size)!!
                println("decrypted message:" + String(decryptedMessage.copyOfRange(0,decryptedMessageLength)))
                println("decrypted message length: " + decryptedMessageLength)
                return
            }
            if (handshakestate?.action == HandshakeState.COMPLETE && role == HandshakeState.INITIATOR) {
                var encryptedMessage = ByteArray(65535)
                var encryptedMessageLength = 0
                val s1 = "Hello World"
                encryptedMessageLength = cipherStatePair?.sender?.encryptWithAd(null, s1.toByteArray(), 0, encryptedMessage,0, s1.toByteArray().size)!!
                ctx.writeAndFlush(encryptedMessage.copyOfRange(0,encryptedMessageLength))
                return
            }

            println("end.handshakestate.action:" + handshakestate?.action)
        }
    }

}
