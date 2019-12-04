package io.libp2p.discovery

import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import java.util.concurrent.CompletableFuture

typealias PeerListener = (PeerInfo) -> Unit

class MDnsDiscovery(
    private val peerId: PeerId
) {
    fun start(): CompletableFuture<Void> {
        TODO("stub")
    }

    fun stop(): CompletableFuture<Void> {
        TODO("stub")
    }

    fun onPeerFound(listener: PeerListener) { }
}