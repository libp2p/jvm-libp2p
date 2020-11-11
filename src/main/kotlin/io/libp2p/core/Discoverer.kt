package io.libp2p.core

import java.util.concurrent.CompletableFuture

typealias PeerListener = (PeerInfo) -> Unit

interface Discoverer {
    fun start(): CompletableFuture<Void>
    fun stop(): CompletableFuture<Void>
    val newPeerFoundListeners: MutableCollection<PeerListener>
}
