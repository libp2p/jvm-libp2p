package io.libp2p.core.multiformats

import com.google.common.base.Utf8
import com.google.common.net.InetAddresses
import io.libp2p.core.PeerId
import io.libp2p.etc.encode.Base58
import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.writeUvarint
import io.netty.buffer.ByteBuf
import org.apache.commons.codec.binary.Base32
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import io.netty.buffer.Unpooled.buffer as byteBuf

/**
 * Enumeration of protocols supported by [Multiaddr]
 * Partially translated from https://github.com/multiformats/java-multiaddr
 */
enum class Protocol(
    val code: Int,
    val sizeBits: Int,
    val typeName: String,
    private val parser: (Protocol, String) -> ByteArray = NO_PARSER,
    private val stringifier: (Protocol, ByteArray) -> String = NO_STRINGIFIER,
    private val validator: (Protocol, ByteArray?) -> Unit = SIZE_VALIDATOR,
    val isPath: Boolean = false
) {

    IP4(4, 32, "ip4", IP4_PARSER, IP4_STRINGIFIER),
    TCP(6, 16, "tcp", UINT16_PARSER, UINT16_STRINGIFIER),
    UDP(273, 16, "udp", UINT16_PARSER, UINT16_STRINGIFIER),
    DCCP(33, 16, "dccp", UINT16_PARSER, UINT16_STRINGIFIER),
    IP6(41, 128, "ip6", IP6_PARSER, IP6_STRINGIFIER),
    IP6ZONE(42, LENGTH_PREFIXED_VAR_SIZE, "ip6zone", UTF8_PARSER, UTF8_STRINGIFIER, UTF8_VALIDATOR),
    DNS4(54, LENGTH_PREFIXED_VAR_SIZE, "dns4", UTF8_PARSER, UTF8_STRINGIFIER, UTF8_VALIDATOR),
    DNS6(55, LENGTH_PREFIXED_VAR_SIZE, "dns6", UTF8_PARSER, UTF8_STRINGIFIER, UTF8_VALIDATOR),
    DNSADDR(56, LENGTH_PREFIXED_VAR_SIZE, "dnsaddr", UTF8_PARSER, UTF8_STRINGIFIER, UTF8_VALIDATOR),
    SCTP(132, 16, "sctp", UINT16_PARSER, UINT16_STRINGIFIER),
    UTP(301, 0, "utp"),
    UDT(302, 0, "udt"),
    UNIX(400, LENGTH_PREFIXED_VAR_SIZE, "unix", UNIX_PATH_PARSER, UTF8_STRINGIFIER, UTF8_VALIDATOR, isPath = true),
    IPFS(421, LENGTH_PREFIXED_VAR_SIZE, "ipfs", BASE58_PARSER, BASE58_STRINGIFIER, PEER_ID_VALIDATOR),
    P2P(421, LENGTH_PREFIXED_VAR_SIZE, "p2p", BASE58_PARSER, BASE58_STRINGIFIER, PEER_ID_VALIDATOR),
    HTTPS(443, 0, "https"),
    ONION(444, 96, "onion", ONION_PARSER, ONION_STRINGIFIER),
    QUIC(460, 0, "quic"),
    WS(477, 0, "ws"),
    P2PCIRCUIT(290, 0, "p2p-circuit"),
    HTTP(480, 0, "http");

    val encoded: ByteArray = encode(code)
    val hasValue: Boolean get() = sizeBits != 0

    private fun encode(type: Int): ByteArray =
        byteBuf(4).writeUvarint(type).toByteArray()

    fun validate(bytes: ByteArray?) {
        validator(this, bytes)
    }

    fun addressToBytes(addr: String): ByteArray = parser(this, addr)

    fun bytesToAddress(addressBytes: ByteArray): String {
        return stringifier(this, addressBytes)
    }

    fun readAddressBytes(buf: ByteBuf): ByteArray? {
        if (!hasValue) return null
        val size = if (sizeBits != LENGTH_PREFIXED_VAR_SIZE) sizeBits / 8 else buf.readUvarint().toInt()
        require(size >= 0) { "Invalid size: $size" }
        require(size <= buf.readableBytes()) { "Var size $size > readable bytes: ${buf.readableBytes()}" }
        val bb = ByteArray(size)
        buf.readBytes(bb)
        return bb
    }

    fun writeAddressBytes(buf: ByteBuf, bytes: ByteArray?) {
        if (bytes != null) {
            if (sizeBits == LENGTH_PREFIXED_VAR_SIZE) buf.writeUvarint(bytes.size)
            buf.writeBytes(bytes)
        }
    }

    companion object {
        @JvmStatic
        val PEER_ID_PROTOCOLS = listOf(P2P, IPFS)

        private val byCode = values().associateBy { p -> p.code }
        private val byName = values().associateBy { p -> p.typeName }

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

private const val LENGTH_PREFIXED_VAR_SIZE = -1

private val SIZE_VALIDATOR: (Protocol, ByteArray?) -> Unit = { protocol, bytes ->
    if (!protocol.hasValue && bytes != null)
        throw IllegalArgumentException("No value expected for protocol $protocol, but got ${bytes.contentToString()}")
    if (protocol.hasValue) {
        requireNotNull(bytes) { "Non-null value expected for protocol $protocol" }
        if (protocol.sizeBits != LENGTH_PREFIXED_VAR_SIZE && bytes.size * 8 != protocol.sizeBits) {
            throw IllegalArgumentException("Value of size ${protocol.sizeBits / 8} expected for protocol $protocol but got ${bytes.contentToString()}")
        }
    }
}
private val UTF8_VALIDATOR: (Protocol, ByteArray?) -> Unit = { protocol, bytes ->
    requireNotNull(bytes) { "Non-null value expected for protocol $protocol" }
    require(bytes.isNotEmpty()) { "Non-empty value expected for protocol $protocol" }
    if (!Utf8.isWellFormed(bytes)) {
        throw IllegalArgumentException("Malformed UTF-8")
    }
}
private val PEER_ID_VALIDATOR: (Protocol, ByteArray?) -> Unit = { protocol, bytes ->
    requireNotNull(bytes) { "Non-null value expected for PeerId in $protocol" }
    PeerId(bytes) // constructor validates array size
}

private val NO_PARSER: (Protocol, String) -> ByteArray = { protocol, _ ->
    throw IllegalArgumentException("No value serializer for protocol $protocol")
}
private val NO_STRINGIFIER: (Protocol, ByteArray) -> String = { protocol, _ ->
    throw IllegalArgumentException("No value deserializer for protocol $protocol")
}
private val IP4_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    val inetAddr = InetAddresses.forString(addr)
    if (inetAddr !is Inet4Address) {
        throw IllegalArgumentException("The address is not IPv4 address: $addr")
    }
    inetAddr.address
}
private val IP4_STRINGIFIER: (Protocol, ByteArray) -> String = { _, bytes ->
    InetAddresses.toAddrString(InetAddress.getByAddress(bytes))
}
private val IP6_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    val inetAddr = InetAddresses.forString(addr)
    if (inetAddr !is Inet6Address) {
        throw IllegalArgumentException("The address is not IPv6 address: $addr")
    }
    inetAddr.address
}
private val IP6_STRINGIFIER: (Protocol, ByteArray) -> String = { _, bytes ->
    InetAddresses.toAddrString(InetAddress.getByAddress(bytes))
}
private val UINT16_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    val x = Integer.parseInt(addr)
    if (x < 0 || x > 65535) throw IllegalArgumentException("Failed to parse $addr value (expected 0 <= $x < 65536")
    byteBuf(2).writeShort(x).toByteArray()
}
private val UINT16_STRINGIFIER: (Protocol, ByteArray) -> String = { _, bytes ->
    bytes.toByteBuf().readUnsignedShort().toString()
}
private val UTF8_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    addr.toByteArray(StandardCharsets.UTF_8)
}
private val UTF8_STRINGIFIER: (Protocol, ByteArray) -> String = { _, bytes ->
    String(bytes, StandardCharsets.UTF_8)
}
private val UNIX_PATH_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    val addr1 = if (addr.startsWith("/")) addr.substring(1) else addr
    addr1.toByteArray(StandardCharsets.UTF_8)
}
private val BASE58_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    Base58.decode(addr)
}
private val BASE58_STRINGIFIER: (Protocol, ByteArray) -> String = { _, bytes ->
    Base58.encode(bytes)
}
private val ONION_PARSER: (Protocol, String) -> ByteArray = { _, addr ->
    val split = addr.split(":")
    if (split.size != 2) throw IllegalArgumentException("Onion address needs a port: $addr")
    // onion address without the ".onion" substring
    if (split[0].length != 16) throw IllegalArgumentException("failed to parse addr: $addr not a Tor onion address.")

    val base32 = Base32()
    val base32Text = split[0].uppercase()
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
private val ONION_STRINGIFIER: (Protocol, ByteArray) -> String = { _, bytes ->
    val byteBuf = bytes.toByteBuf()
    val host = byteBuf.readBytes(10).toByteArray()
    val port = byteBuf.readUnsignedShort()
    String(Base32().encode(host)).lowercase() + ":" + port
}
