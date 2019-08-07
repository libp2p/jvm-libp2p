package io.libp2p.core.security.noise

import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.events.SecureChannelFailed
import io.libp2p.core.events.SecureChannelInitialized
import io.libp2p.core.protocol.Mode
import io.libp2p.core.protocol.ProtocolBindingInitializer
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.types.toByteBuf
import io.libp2p.core.util.replace
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import java.security.PublicKey
import java.util.concurrent.CompletableFuture

class NoiseSecureChannel(val localKey: PrivKey, val remotePeerId: PeerId? = null) :
    SecureChannel {
    private val HandshakeHandlerName = "NoiseHandshake"

    override val announce = "/noise/ix/25519/chachapoly/sha256/0.1.0"
    override val matcher = ProtocolMatcher(Mode.STRICT, name = "/noise/ix/25519/chachapoly/sha256/0.1.0")
    // TODO: the announce and matcher values must be more flexible in order to be able to respond appropriately
    // currently, these are fixed string values, and cannot be parsed out for dynamic announcements coming others

    override fun initializer(): ProtocolBindingInitializer<SecureChannel.Session> {
        val ret = CompletableFuture<SecureChannel.Session>()

        return ProtocolBindingInitializer(
            object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().replace(this,
                        listOf("NoiseHandler" to NoiseIoHandshake()))
                }
            }, ret
        )
    }

    inner class NoiseIoHandshake : ChannelInboundHandlerAdapter() {
        private var negotiator: HandshakeState? = null
        private var activated = false

        fun activate(ctx: ChannelHandlerContext) {
            if (!activated) {
                activated = true
                negotiator = HandshakeState("Noise_IX_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)

                // requires an initiator identity key for connections
                // which is separate from other key usages
                // the ed25519 private key is likely a candidate shared server key

                // create the static key
                // TODO: consider options for obtaining the static key
                val staticKeyPair = negotiator!!.localKeyPair
                staticKeyPair.generateKeyPair()

                // create a local ephemeral key
                val ephKeyPair = negotiator!!.fixedEphemeralKey
                ephKeyPair.generateKeyPair()

                // as the protocol takes shape, there are combinations of ee, se, es, ss DH computations
                // complete them, either from within the Noise framework, or here

                // generate the chaining key for use with transport messages



                // TODO: eventually, figure out how to use BouncyCastle for the appropriate keys
            }
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any?) {
            activate(ctx)

            // need to obtain the responder's static key

            // use appropriate chaining key for decrypting transport messages
            // initiator and responder should have a shared symmetric key for transport messages
            // use Noise, or do it here

            // use appropriate key for writing transport
            // as above, use Noise, or do it here

        }
    }

}

/**
 * NoiseSession exposes the identities and keys for the Noise protocol. TODO: This has not been adapted for Noise.
 */
class NoiseSession(
    override val localId: PeerId,
    override val remoteId: PeerId,
    override val remotePubKey: PublicKey
) : SecureChannel.Session