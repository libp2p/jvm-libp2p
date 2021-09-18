package io.libp2p.core.multistream

import io.libp2p.core.Host
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.PeerId
import io.libp2p.core.StreamPromise
import io.libp2p.core.multiformats.Multiaddr
import java.util.concurrent.CompletableFuture

/**
 * A ProtocolBinding represents the entrypoint to a protocol.
 *
 * It provides metadata about the protocol (such as its protocol ID and matching heuristics), and activation logic.
 *
 * A protocol can act on a Connection (as is the case of StreamMuxers and SecureChannels), or it can be deployed on a
 * Stream (e.g. user-land protocols such as pubsub, DHT, etc.)
 */
interface ProtocolBinding<out TController> {

    /**
     * Supported protocol ids for this binding
     */
    val protocolDescriptor: ProtocolDescriptor

    /**
     * Dials the specified peer, and attempts to connect this Protocol
     */
    @JvmDefault
    fun dial(host: Host, addrWithPeer: Multiaddr): StreamPromise<out TController> {
        val (peerId, addr) = addrWithPeer.toPeerIdAndAddr()
        return dial(host, peerId, addr)
    }

    @JvmDefault
    fun dial(host: Host, peer: PeerId, vararg addr: Multiaddr): StreamPromise<out TController> {
        return host.newStream(
            protocolDescriptor.announceProtocols,
            peer,
            *addr
        )
    } // dial

    /**
     * Returns initializer for this protocol on the provided channel, together with an optional controller object.
     */
    fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out TController>

    /**
     * If the [matcher] of this binding is not [Mode.STRICT] then it can't play initiator role since
     * it doesn't know the exact protocol ids. This method converts this binding to
     * _initiator_ binding with explicit protocol id
     */
    @JvmDefault
    fun toInitiator(protocols: List<ProtocolId>): ProtocolBinding<TController> {
        if (!protocolDescriptor.matchesAny(protocols)) throw Libp2pException("This binding doesn't support $protocols")
        val srcBinding = this
        return object : ProtocolBinding<TController> {
            override val protocolDescriptor = ProtocolDescriptor(protocols, srcBinding.protocolDescriptor.protocolMatcher)
            override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out TController> =
                srcBinding.initChannel(ch, selectedProtocol)
        }
    }

    companion object {
        /**
         * Creates a [ProtocolBinding] instance with [Mode.STRICT] [matcher] and specified [handler]
         */
        fun <T> createSimple(protocolName: ProtocolId, handler: P2PChannelHandler<T>): ProtocolBinding<T> {
            return object : ProtocolBinding<T> {
                override val protocolDescriptor = ProtocolDescriptor(protocolName)
                override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out T> {
                    return handler.initChannel(ch)
                }
            }
        }
    }
}
