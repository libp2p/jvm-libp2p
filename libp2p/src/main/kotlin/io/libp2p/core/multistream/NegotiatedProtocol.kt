package io.libp2p.core.multistream

import io.libp2p.core.P2PChannel
import java.util.concurrent.CompletableFuture

/**
 * Represents [ProtocolBinding] with exact protocol version which was agreed on
 */
open class NegotiatedProtocol<TController, TBinding : ProtocolBinding<TController>>(
    val binding: TBinding,
    val protocol: ProtocolId
) {
    open fun initChannel(ch: P2PChannel): CompletableFuture<out TController> =
        binding.initChannel(ch, protocol)
}
