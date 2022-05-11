package io.libp2p.core.multiformats

import com.google.common.net.InetAddresses
import io.libp2p.core.PeerId
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toHex
import io.libp2p.etc.types.writeUvarint
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class MultiaddrTest {

    companion object {
        private fun Long.toVarInt() = Unpooled.buffer().writeUvarint(this).toByteArray()

        @JvmStatic
        fun paramsInvalid() = listOf(
            "/ip4",
            "/ip4/::1",
            "/ip4/0:0:0:0:0:0:0:1",
            "/ip4/consensys.net",
            "/ip4/123.2.3",
            "/ip4/123.2.3.5.6",
            "/ip4/123.2.3.256",
            "/ip4/fdpsofodsajfdoisa",
            "/ip6",
            "/ip6/consensys.net",
            "/ip6/127.0.0.1",
            "/ip6/0:0:0:0:0:0:0:1::",
            "/ip6/0:0:0:0:0:0:0:1:2",
            "/ip6/0:0:0:0:0:0:1",
            "/ip6zone",
            "/ip6zone/",
            "/ip6zone//ip6/fe80::1",
            "/udp",
            "/tcp",
            "/sctp",
            "/udp/65536",
            "/udp/-2",
            "/udp/abc",
            "/udp/",
            "/udp//",
            "/udp/ /",
            "/udp /123",
            "/udp/123 ",
            "/udp/123\n",
            "/udp/\n123",
            "/udp\n/123",
            "/\nudp/123",
            "\n/udp/123",
            "/ udp/123",
            "udp/123",
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
            "/ip4/1.2.3.4/tcp/80/unix",
            // too short PeerId
            "/p2p/QmcgpsyWgH8Y8ajJz1Cu72",
            // too long PeerId
            "/p2p/QmcgpsyWgH8Y8ajJz1Cu72KnS5uo2Aa2LpzU7kinSupNKC55555555555555555555555555555555555"
        )

        @JvmStatic
        fun paramsValid() = listOf(
            "/ip4/1.2.3.4",
            "/ip4/0.0.0.0",
            "/ip6/0:0:0:0:0:0:0:1",
            "/ip6/2601:9:4f81:9700:803e:ca65:66e8:c21",
            "/ip6/2601:9:4f81:9700:803e:ca65:66e8:c21/udp/1234/quic",
            "/ip6/1::",
            "/ip6/1::2323:abcd",
            "/ip6/::",
            "/ip6/::ffff",
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

        @JvmStatic
        fun protocolLists() = listOf(
            Arguments.of(
                listOf(
                    MultiaddrComponent(Protocol.IP4, "7f000001".fromHex()),
                    MultiaddrComponent(Protocol.TCP, "2328".fromHex())
                ),
                "/ip4/127.0.0.1/tcp/9000"
            ),
            Arguments.of(
                listOf(
                    MultiaddrComponent(Protocol.IP4, "7f000001".fromHex()),
                    MultiaddrComponent(Protocol.TCP, "2328".fromHex()),
                    MultiaddrComponent(Protocol.WS, null)
                ),
                "/ip4/127.0.0.1/tcp/9000/ws"
            )
        )

        @JvmStatic
        fun splitParams() = listOf(
            Arguments.of(
                "/ip4/127.0.0.1/tcp/20000/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6",
                listOf("/ip4/127.0.0.1/tcp/20000/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6")
            ),
            Arguments.of(
                "/ip4/127.0.0.1/tcp/20000/dns4/made.up.host/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6",
                listOf("/ip4/127.0.0.1/tcp/20000", "/dns4/made.up.host/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6")
            ),
            Arguments.of(
                "/dns4/made.up.host/tcp/20000/ip4/127.0.0.1/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6",
                listOf("/dns4/made.up.host/tcp/20000/ip4/127.0.0.1/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6")
            ),
            Arguments.of(
                "/dns4/made.up.host/tcp/20000/dns4/a.different.host/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6",
                listOf("/dns4/made.up.host/tcp/20000", "/dns4/a.different.host/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6")
            ),
            Arguments.of(
                "/dns4/made.up.host/tcp/20000/dns4/a.different.host/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6/dns4/lets.go.crazy",
                listOf("/dns4/made.up.host/tcp/20000", "/dns4/a.different.host/ipfs/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6", "/dns4/lets.go.crazy")
            )
        )

        @JvmStatic
        fun invalidSerializations() = listOf(
            // Invalid var lengths
            Arguments.of(Protocol.DNS4.encoded + 1L.toVarInt()),
            Arguments.of(Protocol.DNS4.encoded + 10L.toVarInt() + ByteArray(9, { 0 })),
            Arguments.of(Protocol.DNS4.encoded + 65535L.toVarInt() + ByteArray(9, { 0 })),
            Arguments.of(Protocol.DNS4.encoded + Int.MAX_VALUE.toLong().toVarInt() + ByteArray(9, { 0 })),
            Arguments.of(Protocol.DNS4.encoded + (Int.MAX_VALUE.toLong() + 1).toVarInt() + ByteArray(9, { 0 })),
            Arguments.of(Protocol.DNS4.encoded + Long.MAX_VALUE.toVarInt() + ByteArray(9, { 0 })),
            // Invalid UTF-8
            Arguments.of(Protocol.DNS4.encoded + 1L.toVarInt() + 0x80.toByte()),
            Arguments.of(Protocol.DNS4.encoded + 1L.toVarInt() + 0xbf.toByte()),
        )
    }

    @ParameterizedTest
    @MethodSource("paramsInvalid")
    fun invalidStringAddress(addr: String) {
        assertThrows(IllegalArgumentException::class.java, { Multiaddr(addr) })
    }

    @ParameterizedTest
    @MethodSource("paramsValid")
    fun validStringAddress(addr: String) {
        val multiaddr = Multiaddr(addr)
        val bytes = multiaddr.serialize()
        val multiaddr1 = Multiaddr.deserialize(bytes)
        assertEquals(
            toCanonical(addr),
            multiaddr1.toString().lowercase()
        )
    }

    fun toCanonical(multiaddr: String): String {
        val multiaddr1 = multiaddr
            .lowercase().trimEnd('/').replace("/ipfs/", "/p2p/")
        val idx1 = multiaddr1.indexOf("/ip6/")
        return if (idx1 >= 0) {
            val ip6Idx = idx1 + "/ip6/".length
            val idx2 = multiaddr1.indexOf("/", ip6Idx).let {
                if (it < 0) multiaddr1.length else it
            }
            val ip6 = multiaddr1.substring(ip6Idx, idx2)
            val canonIp6 = InetAddresses.toAddrString(InetAddresses.forString(ip6))
            multiaddr1.take(ip6Idx) + canonIp6 + multiaddr1.drop(idx2)
        } else {
            multiaddr1
        }
    }

    @ParameterizedTest
    @MethodSource("toBytesParams")
    fun toBytes(str: String, bytes: ByteArray) {
        assertEquals(bytes.toHex(), Multiaddr(str).serialize().toHex())
        assertEquals(str, Multiaddr.deserialize(bytes).toString())
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

    @ParameterizedTest
    @MethodSource("protocolLists")
    fun testFromProtocolList(protocols: List<MultiaddrComponent>, expected: String) {
        assertEquals(expected, Multiaddr(protocols).toString())
    }

    @Test
    fun testMakeWithPeerId() {
        val parentAddr = Multiaddr("/ip4/127.0.0.1/tcp/20000")
        val peerId = testPeerId()

        val addr = parentAddr.withP2P(peerId)
        assertEquals("/ip4/127.0.0.1/tcp/20000/p2p/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6", addr.toString())
        assertEquals(addr.withP2P(peerId), addr)

        assertThrows(IllegalArgumentException::class.java) {
            addr.withP2P(PeerId.random()) // parent has another peer id
        }
    }

    @Test
    fun `concatenated() should just concat components`() {
        val parentAddr = Multiaddr("/ip4/127.0.0.1/tcp/20000")
        val childAddr = Multiaddr("/p2p-circuit/ip4/127.0.0.2")

        val addr = parentAddr.concatenated(childAddr)
        assertEquals(
            "/ip4/127.0.0.1/tcp/20000/p2p-circuit/ip4/127.0.0.2",
            addr.toString()
        )
    }

    @Test
    fun `merged() should succeed with distinct components`() {
        val parentAddr = Multiaddr("/ip4/127.0.0.1/tcp/20000")
        val childAddr = Multiaddr("/p2p-circuit/dns4/trousers.org")

        val addr = parentAddr.merged(childAddr)
        assertEquals(
            "/ip4/127.0.0.1/tcp/20000/p2p-circuit/dns4/trousers.org",
            addr.toString()
        )
    }

    @Test
    fun `merged() should succeed with matching component values`() {
        val parentAddr = Multiaddr("/ip4/127.0.0.1/tcp/20000")
        val childAddr = Multiaddr("/ip4/127.0.0.1/p2p-circuit/dns4/trousers.org")

        val addr = parentAddr.merged(childAddr)
        assertEquals(
            "/ip4/127.0.0.1/tcp/20000/p2p-circuit/dns4/trousers.org",
            addr.toString()
        )
    }

    @Test
    fun `merged() should throw with non-matching component values`() {
        val parentAddr = Multiaddr("/ip4/127.0.0.1/tcp/20000")
        val childAddr = Multiaddr("/ip4/127.0.0.1/tcp/30000/p2p-circuit/dns4/trousers.org")

        assertThrows(IllegalArgumentException::class.java) {
            parentAddr.merged(childAddr)
        }
    }

    @Test
    fun testGetPeerId() {
        val addr = Multiaddr("/ip4/127.0.0.1/tcp/20000/p2p/QmULzn6KtFUCKpkFymEUgUvkLtv9j2Eo4utZPELmQEebR6")

        assertEquals(testPeerId(), addr.getPeerId())
        assertEquals(Multiaddr("/ip4/127.0.0.1/tcp/20000").getPeerId(), null)
    }

    @ParameterizedTest
    @MethodSource("splitParams")
    fun splitMultiAddr(addr: Multiaddr, expected: List<String>) {
        val split = addr.split { it.equals(Protocol.DNS4) }

        assertEquals(expected, split.map { it.toString() })
    }

    @ParameterizedTest
    @MethodSource("invalidSerializations")
    fun `deserialize() with invalid bytes should throw`(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) {
            Multiaddr.deserialize(bytes)
        }
    }

    private fun testPeerId(): PeerId {
        val idHex = "1220593cd036d6ac062ca1c332c15aca7a7b8ed7c9a004b34046e58f2aa6439102b5"
        val peerId = PeerId(idHex.fromHex())
        return peerId
    }
}
