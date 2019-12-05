package io.libp2p.core.multiformats

class MultiaddrDns {
    companion object {
        fun resolve(addr: Multiaddr): List<Multiaddr> {
            return listOf(addr)
        }
    }
}