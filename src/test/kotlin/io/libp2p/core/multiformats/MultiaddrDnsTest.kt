package io.libp2p.core.multiformats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class MultiaddrDnsTest {
    @ParameterizedTest
    @MethodSource("noResolutionNeeded")
    fun `no resolution needed`(addr: String) {
        val multiaddr = Multiaddr(addr)

        val resolved = MultiaddrDns.resolve(multiaddr)
        assertEquals(1, resolved.size)
        assertEquals(multiaddr, resolved[0])
    }

    companion object {
        @JvmStatic
        fun noResolutionNeeded() = listOf(
            "/ip4/1.2.3.4",
            "/ip4/0.0.0.0",
            "/ip6/0:0:0:0:0:0:0:1",
            "/ip6/2601:9:4f81:9700:803e:ca65:66e8:c21",
            "/ip6/2601:9:4f81:9700:803e:ca65:66e8:c21/udp/1234/quic",
            "/ip4/127.0.0.1/udp/1234",
            "/ip4/127.0.0.1/udp/0",
            "/ip4/127.0.0.1/tcp/1234",
            "/ip4/127.0.0.1/udp/1234/quic",
            "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
            "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234",
            "/ip4/127.0.0.1/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
            "/ip4/127.0.0.1/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234"
        )
    }
}