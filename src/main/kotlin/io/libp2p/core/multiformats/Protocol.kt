package io.libp2p.core.multiformats

import io.libp2p.core.PeerId
import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.writeUvarint
import io.netty.buffer.ByteBuf
import org.apache.commons.codec.binary.Base32
import java.net.Inet4Address
import java.net.Inet6Address
import java.nio.charset.StandardCharsets
import io.netty.buffer.Unpooled.buffer as byteBuf

private const val LENGTH_PREFIXED_VAR_SIZE = -1

/**
 * Enumeration of protocols supported by [Multiaddr]
 * Partially translated from https://github.com/multiformats/java-multiaddr
 */
enum class Protocol(val code: Int, val size: Int, val typeName: String) {

    IP4(4, 32, "ip4"),
    TCP(6, 16, "tcp"),
    UDP(273, 16, "udp"),
    DCCP(33, 16, "dccp"),
    IP6(41, 128, "ip6"),
    IP6ZONE(42, LENGTH_PREFIXED_VAR_SIZE, "ip6zone"),
    DNS4(54, LENGTH_PREFIXED_VAR_SIZE, "dns4"),
    DNS6(55, LENGTH_PREFIXED_VAR_SIZE, "dns6"),
    DNSADDR(56, LENGTH_PREFIXED_VAR_SIZE, "dnsaddr"),
    SCTP(132, 16, "sctp"),
    UTP(301, 0, "utp"),
    UDT(302, 0, "udt"),
    UNIX(400, LENGTH_PREFIXED_VAR_SIZE, "unix") {
        override fun isPath() = true
    },
    IPFS(421, LENGTH_PREFIXED_VAR_SIZE, "ipfs"),
    P2P(421, LENGTH_PREFIXED_VAR_SIZE, "p2p"),
    HTTPS(443, 0, "https"),
    ONION(444, 96, "onion"),
    QUIC(460, 0, "quic"),
    WS(477, 0, "ws"),
    P2PCIRCUIT(290, 0, "p2p-circuit"),
    HTTP(480, 0, "http");

    val encoded: ByteArray = encode(code)

    private fun encode(type: Int): ByteArray =
        byteBuf(4).writeUvarint(type).toByteArray()

    open fun isPath() = false

    fun addressToBytes(addr: String): ByteArray =
        when (this) {
            IP4 -> {
                val inetAddr = Inet4Address.getByName(addr)
                if (inetAddr !is Inet4Address) {
                    throw IllegalArgumentException("The address is not IP4 address: $addr")
                }
                inetAddr.address
            }
            IP6 -> Inet6Address.getByName(addr).address
            TCP, UDP, DCCP, SCTP -> {
                val x = Integer.parseInt(addr)
                if (x > 65535) throw IllegalArgumentException("Failed to parse $this address $x > 65535")
                byteBuf(2).writeShort(x).toByteArray()
            }
            IPFS, P2P -> {
                val hashBytes = PeerId.fromBase58(addr).bytes
                byteBuf(32)
                    .writeBytes(hashBytes)
                    .toByteArray()
            }
            ONION -> {
                val split = addr.split(":")
                if (split.size != 2) throw IllegalArgumentException("Onion address needs a port: $addr")
                // onion address without the ".onion" substring
                if (split[0].length != 16) throw IllegalArgumentException("failed to parse $this addr: $addr not a Tor onion address.")

                val base32 = Base32()
                val base32Text = split[0].toUpperCase()
                if (!base32.isInAlphabet(base32Text)) throw IllegalArgumentException("Invalid Base32 string in the Onion address: $base32Text")
                val onionHostBytes = base32.decode(base32Text)
                val port = split[1].toInt()
                if (port > 65535) throw IllegalArgumentException("Port is > 65535: $port")
                if (port < 1) throw IllegalArgumentException("Port is < 1: $port")

                byteBuf(18)
                    .writeBytes(onionHostBytes)
                    .writeShort(port)
                    .toByteArray()
            }
            UNIX -> {
                val addr1 = if (addr.startsWith("/")) addr.substring(1) else addr
                val path = addr1.toByteArray(StandardCharsets.UTF_8)
                byteBuf(path.size + 8)
                    .writeBytes(path)
                    .toByteArray()
            }
            DNS4, DNS6, DNSADDR, IP6ZONE -> {
                val strBytes = addr.toByteArray(StandardCharsets.UTF_8)
                byteBuf(strBytes.size + 8)
                    .writeBytes(strBytes)
                    .toByteArray()
            }
            else -> throw IllegalArgumentException("Unknown multiaddr type: $this")
        }

    fun readAddressBytes(buf: ByteBuf): ByteArray {
        val size = if (size != LENGTH_PREFIXED_VAR_SIZE) size / 8 else buf.readUvarint().toInt()
        val bb = ByteArray(size)
        buf.readBytes(bb)
        return bb
    }
    fun writeAddressBytes(buf: ByteBuf, bytes: ByteArray) {
        if (size == LENGTH_PREFIXED_VAR_SIZE) buf.writeUvarint(bytes.size)
        buf.writeBytes(bytes)
    }

    fun bytesToAddress(addressBytes: ByteArray): String {
        return when (this) {
            IP4 -> {
                Inet4Address.getByAddress(addressBytes)
                    .toString().substring(1)
            }
            IP6 -> {
                Inet6Address.getByAddress(addressBytes)
                    .toString().substring(1)
            }
            TCP, UDP, DCCP, SCTP -> addressBytes.toByteBuf().readUnsignedShort().toString()
            IPFS, P2P -> {
                val addrBuf = addressBytes.toByteBuf()
                PeerId(addrBuf.toByteArray()).toBase58()
            }
            ONION -> {
                val byteBuf = addressBytes.toByteBuf()
                val host = byteBuf.readBytes(10).toByteArray()
                val port = byteBuf.readUnsignedShort()
                String(Base32().encode(host)).toLowerCase() + ":" + port
            }
            UNIX, DNS4, DNS6, DNSADDR, IP6ZONE -> {
                String(addressBytes, StandardCharsets.UTF_8)
            }
            else -> throw IllegalStateException("Unimplemented protocol type: $this")
        }
    }

    companion object {
        private val byCode = values().associate { p -> p.code to p }
        private val byName = values().associate { p -> p.typeName to p }

        @JvmStatic
        fun get(code: Int) = byCode[code]

        @JvmStatic
        fun get(name: String) = byName[name]

        @JvmStatic
        fun getOrThrow(code: Int) = get(code) ?: throw IllegalArgumentException("Unknown protocol code: $code")

        @JvmStatic
        fun getOrThrow(name: String) = get(name) ?: throw IllegalArgumentException("Unknown protocol name: '$name'")
    }
}
