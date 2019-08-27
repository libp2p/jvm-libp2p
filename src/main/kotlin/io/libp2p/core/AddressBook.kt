package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The address book holds known addresses for peers.
 */
interface AddressBook {

    /**
     * Equivalent to getAddrs(id).
     */
    operator fun get(id: PeerId): CompletableFuture<Collection<Multiaddr>?> = getAddrs(id)

    /**
     * Returns the addresses we know for a given peer, or nil if we know zero.
     */
    fun getAddrs(id: PeerId): CompletableFuture<Collection<Multiaddr>?>

    /**
     * Overrides all addresses for a given peer with the specified ones.
     */
    fun setAddrs(id: PeerId, ttl: Long, vararg addrs: Multiaddr): CompletableFuture<Void>

    /**
     * Adds addresses for a peer, replacing the TTL if the address already existed.
     */
    fun addAddrs(id: PeerId, ttl: Long, vararg addrs: Multiaddr): CompletableFuture<Void>
}

class MemoryAddressBook : AddressBook {
    val map = ConcurrentHashMap<PeerId, Collection<Multiaddr>>()

    override fun getAddrs(id: PeerId): CompletableFuture<Collection<Multiaddr>?> {
        return CompletableFuture.completedFuture(map[id])
    }

    override fun setAddrs(id: PeerId, ttl: Long, vararg addrs: Multiaddr): CompletableFuture<Void> {
        map[id] = listOf(*addrs)
        return CompletableFuture.completedFuture(null)
    }

    override fun addAddrs(id: PeerId, ttl: Long, vararg newAddrs: Multiaddr): CompletableFuture<Void> {
        map.compute(id) { _, addrs ->
            (addrs ?: emptyList()) + listOf(*newAddrs)
        }
        return CompletableFuture.completedFuture(null)
    }
}