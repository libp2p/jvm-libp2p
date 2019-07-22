package io.libp2p.core.protocol

import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

/**
 * A ProtocolBinding represents the entrypoint to a protocol.
 *
 * It provides metadata about the protocol (such as its protocol ID and matching heuristics), and activation logic.
 *
 * A protocol can act on a Connection (as is the case of StreamMuxers and SecureChannels), or it can be deployed on a
 * Stream (e.g. user-land protocols such as pubsub, DHT, etc.)
 */
interface ProtocolBinding<TController> {
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
    fun initializer(selectedProtocol: String): ProtocolBindingInitializer<TController>
}

class ProtocolBindingInitializer<TController>(
    val channelInitializer: ChannelHandler,
    val controller: CompletableFuture<TController>
)

class DummyProtocolBinding : ProtocolBinding<Nothing> {
    override val announce: String = "/dummy/0.0.0"
    override val matcher: ProtocolMatcher = ProtocolMatcher(Mode.NEVER)

    override fun initializer(selectedProtocol: String): ProtocolBindingInitializer<Nothing> = TODO("not implemented")
}