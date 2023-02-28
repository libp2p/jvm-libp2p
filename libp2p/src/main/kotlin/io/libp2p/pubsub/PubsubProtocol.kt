package io.libp2p.pubsub

import io.libp2p.core.multistream.ProtocolId
import io.libp2p.core.multistream.ProtocolVersion
import io.libp2p.core.multistream.ProtocolVersion.Companion.parse
import io.libp2p.pubsub.PubsubProtocolType.FLOODSUB
import io.libp2p.pubsub.PubsubProtocolType.GOSSIP

enum class PubsubProtocolType(val announceString: String) {
    GOSSIP("meshsub"),
    FLOODSUB("floodsub")
}

enum class PubsubProtocol(
    val type: PubsubProtocolType,
    val version: ProtocolVersion
): Comparable<PubsubProtocol> {

    Gossip_V_1_0(GOSSIP, parse("1.0.0")),
    Gossip_V_1_1(GOSSIP, parse("1.1.0")),
    Gossip_V_1_2(GOSSIP, parse("1.2.0")),
    Floodsub(FLOODSUB, parse("1.0.0"));

    val announceStr: ProtocolId = "/${type.announceString}/$version"

    companion object {
        fun fromProtocol(protocol: ProtocolId) = PubsubProtocol.values().find { protocol == it.announceStr }
            ?: throw NoSuchElementException("No PubsubProtocol found with protocol $protocol")
    }
}
