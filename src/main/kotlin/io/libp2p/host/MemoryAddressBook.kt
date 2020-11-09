package io.libp2p.host

import io.libp2p.core.AddressBook
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class MemoryAddressBook : AddressBook {
    val map =
        ConcurrentHashMap<PeerId, Collection<Multiaddr>>()

    override fun getAddrs(id: PeerId): CompletableFuture<Collection<Multiaddr>?> {
        return CompletableFuture.completedFuture(map[id])
    }

    override fun setAddrs(id: PeerId, ttl: Long, vararg addrs: Multiaddr): CompletableFuture<Void> {
        map[id] = listOf(*addrs)
        return CompletableFuture.completedFuture(null)
    }

    override fun addAddrs(id: PeerId, ttl: Long, vararg addrs: Multiaddr): CompletableFuture<Void> {
        map.compute(id) { _, existingAddrs ->
            (existingAddrs ?: emptyList()) + listOf(*addrs)
        }
        return CompletableFuture.completedFuture(null)
    }
}
