package io.libp2p.core.multiformats

import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toHex
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class MultiaddrTest {

    companion object {
        @JvmStatic
        fun paramsInvalid() = listOf(
            "/ip4",
            "/ip4/::1",
            "/ip4/fdpsofodsajfdoisa",
            "/ip6",
            "/ip6zone",
            "/ip6zone/",
            "/ip6zone//ip6/fe80::1",
            "/udp",
            "/tcp",
            "/sctp",
            "/udp/65536",
            "/tcp/65536",
            "/quic/65536",
            "/onion/9imaq4ygg2iegci7:80",
            "/onion/aaimaq4ygg2iegci7:80",
            "/onion/timaq4ygg2iegci7:0",
            "/onion/timaq4ygg2iegci7:-1",
            "/onion/timaq4ygg2iegci7",
            "/onion/timaq4ygg2iegci@:666",
            "/udp/1234/sctp",
            "/udp/1234/udt/1234",
            "/udp/1234/utp/1234",
            "/ip4/127.0.0.1/udp/jfodsajfidosajfoidsa",
            "/ip4/127.0.0.1/udp",
            "/ip4/127.0.0.1/tcp/jfodsajfidosajfoidsa",
            "/ip4/127.0.0.1/tcp",
            "/ip4/127.0.0.1/quic/1234",
            "/ip4/127.0.0.1/ipfs",
            "/ip4/127.0.0.1/ipfs/tcp",
            "/ip4/127.0.0.1/p2p",
            "/ip4/127.0.0.1/p2p/tcp",
            "/unix",
            "/ip4/1.2.3.4/tcp/80/unix"
        )

        @JvmStatic
        fun paramsValid() = listOf(
            "/ip4/1.2.3.4",
            "/ip4/0.0.0.0",
            "/ip6/0:0:0:0:0:0:0:1",
            "/ip6/2601:9:4f81:9700:803e:ca65:66e8:c21",
            "/ip6/2601:9:4f81:9700:803e:ca65:66e8:c21/udp/1234/quic",
            "/ip6zone/x/ip6/fe80:0:0:0:0:0:0:1",
            "/ip6zone/x%y/ip6/fe80:0:0:0:0:0:0:1",
            "/ip6zone/x%y/ip6/0:0:0:0:0:0:0:0",
            "/ip6zone/x/ip6/fe80:0:0:0:0:0:0:1/udp/1234/quic",
            "/onion/timaq4ygg2iegci7:1234",
            "/onion/timaq4ygg2iegci7:80/http",
            "/udp/0",
            "/tcp/0",
            "/sctp/0",
            "/udp/1234",
            "/tcp/1234",
            "/sctp/1234",
            "/udp/65535",
            "/tcp/65535",
            "/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
            "/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
            "/udp/1234/sctp/1234",
            "/udp/1234/udt",
            "/udp/1234/utp",
            "/tcp/1234/http",
            "/tcp/1234/https",
            "/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234",
            "/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234",
            "/ip4/127.0.0.1/udp/1234",
            "/ip4/127.0.0.1/udp/0",
            "/ip4/127.0.0.1/tcp/1234",
            "/ip4/127.0.0.1/tcp/1234/",
            "/ip4/127.0.0.1/udp/1234/quic",
            "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
            "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234",
            "/ip4/127.0.0.1/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC",
            "/ip4/127.0.0.1/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234",
            "/unix/a/b/c/d/e",
            "/unix/stdio",
            "/ip4/1.2.3.4/tcp/80/unix/a/b/c/d/e/f",
            "/ip4/127.0.0.1/ipfs/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234/unix/stdio",
            "/ip4/127.0.0.1/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC/tcp/1234/unix/stdio",
            "/ip4/127.0.0.1/tcp/40001/p2p/16Uiu2HAkuqGKz8D6khfrnJnDrN5VxWWCoLU8Aq4eCFJuyXmfakB5"
        )

        @JvmStatic
        fun toBytesParams() = listOf(
            Arguments.of("/ip4/127.0.0.1/udp/1234", "047f000001910204d2".fromHex()),
            Arguments.of("/ip4/127.0.0.1/tcp/4321", "047f0000010610e1".fromHex()),
            Arguments.of("/ip4/127.0.0.1/udp/1234/ip4/127.0.0.1/tcp/4321", "047f000001910204d2047f0000010610e1".fromHex()),
            Arguments.of("/onion/aaimaq4ygg2iegci:80", "bc030010c0439831b48218480050".fromHex())
        )
    }

    @ParameterizedTest
    @MethodSource("paramsInvalid")
    fun invalidStringAddress(addr: String) {
        Assertions.assertThrows(IllegalArgumentException::class.java, { Multiaddr(addr) })
    }

    @ParameterizedTest
    @MethodSource("paramsValid")
    fun validStringAddress(addr: String) {
        val multiaddr = Multiaddr(addr)
        val bytes = multiaddr.getBytes()
        val multiaddr1 = Multiaddr(bytes)
        assertEquals(addr.toLowerCase().trimEnd('/').replace("/ipfs/", "/p2p/"),
            multiaddr1.toString().toLowerCase())
    }

    @ParameterizedTest
    @MethodSource("toBytesParams")
    fun toBytes(str: String, bytes: ByteArray) {
        assertEquals(bytes.toHex(), Multiaddr(str).getBytes().toHex())
        assertEquals(str, Multiaddr(bytes).toString())
    }

    @Test
    fun testEqualsHashcode() {
        assertEquals(Multiaddr("/ip4/0.0.0.0/tcp/20000"), Multiaddr("/ip4/0.0.0.0/tcp/20000"))
        assertEquals(Multiaddr("/ip4/0.0.0.0/tcp/20000").hashCode(), Multiaddr("/ip4/0.0.0.0/tcp/20000").hashCode())
        assertNotEquals(Multiaddr("/ip4/0.0.0.0/tcp/20001"), Multiaddr("/ip4/0.0.0.0/tcp/20000"))
        assertNotEquals(Multiaddr("/ip4/0.0.0.1/tcp/20000"), Multiaddr("/ip4/0.0.0.0/tcp/20000"))
        assertNotEquals(Multiaddr("/ip4/0.0.0.0/tcp/20001").hashCode(), Multiaddr("/ip4/0.0.0.0/tcp/20000").hashCode())
        assertNotEquals(Multiaddr("/ip4/0.0.0.1/tcp/20000").hashCode(), Multiaddr("/ip4/0.0.0.0/tcp/20000").hashCode())
    }
}