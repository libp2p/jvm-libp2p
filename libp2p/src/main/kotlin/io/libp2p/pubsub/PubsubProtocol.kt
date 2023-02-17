package io.libp2p.pubsub

import io.libp2p.core.multistream.ProtocolId

enum class PubsubProtocol(val announceStr: ProtocolId) {

    Gossip_V_1_0("/meshsub/1.0.0"),
    Gossip_V_1_1("/meshsub/1.1.0"),
    Floodsub("/floodsub/1.0.0");

    companion object {
        fun fromProtocol(protocol: ProtocolId) = PubsubProtocol.values().find { protocol == it.announceStr }
            ?: throw NoSuchElementException("No PubsubProtocol found with protocol $protocol")
    }
}
