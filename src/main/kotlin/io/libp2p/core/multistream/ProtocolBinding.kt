package io.libp2p.core.multistream

import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PAbstractChannel
import io.libp2p.core.P2PAbstractHandler
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
     * The protocol ID with which we'll announce this protocol to peers.
     */
    val announce: String

    /**
     * Matching heuristic to be used on inbound negotiations. If positive, it leads to activation of this protocol.
     */
    val matcher: ProtocolMatcher

    /**
     * Returns initializer for this protocol on the provided channel, together with an optional controller object.
     */
    fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<out TController>

    /**
     * If the [matcher] of this binding is not [Mode.STRICT] then it can't play initiator role since
     * it doesn't know the exact protocol ids. This method converts this binding to
     * _initiator_ binding with explicit protocol id
     */
    @JvmDefault
    fun toInitiator(protocol: String): ProtocolBinding<TController> {
        if (!matcher.matches(protocol)) throw Libp2pException("This binding doesn't support $protocol")
        val srcBinding = this
        return object : ProtocolBinding<TController> {
            override val announce = protocol
            override val matcher = ProtocolMatcher(Mode.STRICT, announce)
            override fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<out TController> =
                srcBinding.initChannel(ch, selectedProtocol)
        }
    }

    companion object {
        /**
         * Creates a [ProtocolBinding] instance with [Mode.STRICT] [matcher] and specified [handler]
         */
        fun <T> createSimple(protocolName: String, handler: P2PAbstractHandler<T>): ProtocolBinding<T> {
            return object : ProtocolBinding<T> {
                override val announce = protocolName
                override val matcher = ProtocolMatcher(Mode.STRICT, announce)
                override fun initChannel(ch: P2PAbstractChannel, selectedProtocol: String): CompletableFuture<out T> {
                    return handler.initChannel(ch)
                }
            }
        }
    }
}
