package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr

data class PeerInfo(
    val peerId: PeerId,
    val addresses: List<Multiaddr>
)
