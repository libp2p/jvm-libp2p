package io.libp2p.core.multiformats

import io.libp2p.tools.DnsAvailability.Companion.assumeIp4Dns
import io.libp2p.tools.DnsAvailability.Companion.assumeIp6Dns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
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

    @ParameterizedTest
    @MethodSource("dns4Localhost")
    fun `resolve dns4 localhost`(addr: String, expected: String) {
        assumeIp4Dns()
        val multiaddr = Multiaddr(addr)

        val resolved = MultiaddrDns.resolve(multiaddr)
        assertEquals(1, resolved.size)
        assertEquals(Multiaddr(expected), resolved[0])
    }

    @ParameterizedTest
    @MethodSource("dns6Localhost")
    fun `resolve dns6 localhost`(addr: String, expected: String) {
        assumeIp6Dns()
        val multiaddr = Multiaddr(addr)

        val resolved = MultiaddrDns.resolve(multiaddr)
        assertEquals(1, resolved.size)
        assertEquals(Multiaddr(expected), resolved[0])
    }

    @ParameterizedTest
    @MethodSource("dnsResolvesToMultipleAddresses")
    fun `hostname resolves to multiple addresses`(addr: String, expected: List<String>) {
        val multiaddr = Multiaddr(addr)
        val expectedMultiaddr = expected.map { Multiaddr(it) }

        val resolved = MultiaddrDns.resolve(multiaddr, TestResolver)
        assertEquals(expected.size, resolved.size)
        assertEquals(expectedMultiaddr, resolved)
    }

    @ParameterizedTest
    @MethodSource("unresolvableAddresses")
    fun `unresolvable addresses return an empty result`(addr: String) {
        val multiaddr = Multiaddr(addr)

        val resolved = MultiaddrDns.resolve(multiaddr)
        assertTrue(resolved.isEmpty())
    }

    companion object {
        @JvmStatic
        fun unresolvableAddresses() = listOf(
            "/dns4/host.invalid",
            "/dns6/host.invalid",
            "/dns4/art-history-coloring-arrogance-fruit-stick.test",
            "/dns6/art-history-coloring-arrogance-fruit-stick.test"
        )

        @JvmStatic
        fun dnsResolvesToMultipleAddresses() = listOf(
            Arguments.of(
                "/dns4/pig.com",
                listOf("/ip4/1.1.1.1", "/ip4/1.1.1.2")
            ),
            Arguments.of(
                "/dns4/pig.com/tcp/8000",
                listOf("/ip4/1.1.1.1/tcp/8000", "/ip4/1.1.1.2/tcp/8000")
            ),
            Arguments.of(
                "/dns4/pig.com/p2p-circuit/dns4/localhost",
                listOf(
                    "/ip4/1.1.1.1/p2p-circuit/ip4/127.0.0.1",
                    "/ip4/1.1.1.2/p2p-circuit/ip4/127.0.0.1"
                )
            ),
            Arguments.of(
                "/dns4/localhost/p2p-circuit/dns4/pig.com",
                listOf(
                    "/ip4/127.0.0.1/p2p-circuit/ip4/1.1.1.1",
                    "/ip4/127.0.0.1/p2p-circuit/ip4/1.1.1.2"
                )
            ),
            Arguments.of(
                "/dns4/pig.com/p2p-circuit/dns4/pog.com",
                listOf(
                    "/ip4/1.1.1.1/p2p-circuit/ip4/2.2.2.1",
                    "/ip4/1.1.1.1/p2p-circuit/ip4/2.2.2.2",
                    "/ip4/1.1.1.2/p2p-circuit/ip4/2.2.2.1",
                    "/ip4/1.1.1.2/p2p-circuit/ip4/2.2.2.2"
                )
            )
        )

        @JvmStatic
        fun dns4Localhost() = listOf(
            Arguments.of(
                "/dns4/localhost",
                "/ip4/127.0.0.1"
            ),
            Arguments.of(
                "/dns4/localhost/tcp/1234",
                "/ip4/127.0.0.1/tcp/1234"
            ),
            Arguments.of(
                "/dns4/localhost/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
                "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC"
            ),
            Arguments.of(
                "/dns4/localhost/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
                "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC"
            )
        )

        @JvmStatic
        fun dns6Localhost() = listOf(
            Arguments.of(
                "/dns6/localhost",
                "/ip6/0:0:0:0:0:0:0:1"
            ),
            Arguments.of(
                "/dns6/localhost/tcp/1234",
                "/ip6/0:0:0:0:0:0:0:1/tcp/1234"
            ),
            Arguments.of(
                "/dns6/localhost/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
                "/ip6/0:0:0:0:0:0:0:1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC"
            ),
            Arguments.of(
                "/dns6/localhost/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
                "/ip6/0:0:0:0:0:0:0:1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC"
            )
        )

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

        val TestResolver = object : MultiaddrDns.Resolver {
            override fun resolveDns4(hostname: String): List<Multiaddr> {
                val address = if ("pig.com".equals(hostname))
                    listOf("/ip4/1.1.1.1", "/ip4/1.1.1.2")
                else if ("localhost".equals(hostname))
                    listOf("/ip4/127.0.0.1")
                else
                    listOf("/ip4/2.2.2.1", "/ip4/2.2.2.2")

                return address.map { Multiaddr(it) }
            }

            override fun resolveDns6(hostname: String): List<Multiaddr> {
                throw NotImplementedError("Not used")
            }
        }
    }
}
