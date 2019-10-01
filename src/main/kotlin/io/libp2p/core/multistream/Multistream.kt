package io.libp2p.core.multistream

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.multistream.MultistreamImpl
import java.util.concurrent.CompletableFuture

/**
 * Represents 'multistream' concept: https://github.com/multiformats/multistream-select
 *
 * This is a handler which can be applied to either [io.libp2p.core.Connection] or [io.libp2p.core.Stream]
 * performs the negotiation with remote party on supported protocol and sets up the corresponding
 * protocol handler.
 *
 * The distinction should be made between _initiator_ and _responder_ [Multistream] roles.
 *
 * The _initiator_ [Multistream] basically has only a single [bindings] entry with desired protocol or
 * a set of bindings for different protocol versions. The first matching protocol is initiated and
 * the protocol [TController] is supplied to the client for further actions
 *
 * The _responder_ [Multistream] basically contains the list of all supported protocols.
 * The protocol is instantiated by a remote request
 */
interface Multistream<TController> : P2PChannelHandler<TController> {

    /**
     * For _responder_ role this is the list of all supported protocols for this peer
     * For _initiator_ role this is the list of protocols the initiator wants to instantiate.
     * Basically this is either a single protocol or a protocol versions
     */
    val bindings: MutableList<ProtocolBinding<TController>>

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController>

    companion object {
        /**
         * Creates empty [Multistream] implementation
         */
        @JvmStatic
        fun <TController> create(): Multistream<TController> = MultistreamImpl()

        /**
         * Creates [Multistream] implementation with a list of protocol bindings
         */
        @JvmStatic
        fun <TController> create(
            vararg bindings: ProtocolBinding<TController>
        ): Multistream<TController> = MultistreamImpl(listOf(*bindings))
        /**
         * Creates [Multistream] implementation with a list of protocol bindings
         */
        @JvmStatic
        fun <TController> create(
            bindings: List<ProtocolBinding<TController>>
        ): Multistream<TController> = MultistreamImpl(bindings)

        /**
         * Creates an _initiator_ [Multistream] with specified [protocol] and [handler]
         */
        @JvmStatic
        fun <TController> initiator(protocol: String, handler: P2PChannelHandler<TController>): Multistream<TController> =
            create(ProtocolBinding.createSimple(protocol, handler))
    }
}
