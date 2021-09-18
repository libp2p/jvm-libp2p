package io.libp2p.core.multistream

import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import java.util.concurrent.CompletableFuture

abstract class StrictProtocolBinding<TController>(
    announce: ProtocolId,
    open val protocol: P2PChannelHandler<TController>
) : ProtocolBinding<TController> {

    override val protocolDescriptor = ProtocolDescriptor(announce)

    override fun initChannel(ch: P2PChannel, selectedProtocol: String): CompletableFuture<out TController> {
        return protocol.initChannel(ch)
    }
}
