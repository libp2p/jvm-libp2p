package io.libp2p.core.multiformats

import io.libp2p.core.PeerId
import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

private val PEER_ID_PROTOCOL = Protocol.P2P

/**
 * Class implements Multiaddress concept: https://github.com/multiformats/multiaddr
 *
 * Multiaddress is basically the chain of components like `protocol: value` pairs
 * (value is optional)
 *
 * It's string representation is `/protocol/value/protocol/value/...`
 * E.g. `/ip4/127.0.0.1/tcp/1234` which means TCP socket on port `1234` on local host
 *
 * @param components: generic Multiaddress representation which is a chain of 'components'
 * represented as a known [Protocol] and its value (if any) serialized to bytes according
 * to this protocol rule
 */
class Multiaddr(val components: List<Pair<Protocol, ByteArray>>) {
    /**
     * Creates instance from the string representation
     */
    constructor(addr: String) : this(parseString(addr))

    /**
     * Creates instance from serialized form from [ByteBuf]
     */
    constructor(bytes: ByteBuf) : this(parseBytes(bytes))
    /**
     * Creates instance from serialized form from [ByteBuf]
     */
    constructor(bytes: ByteArray) : this(parseBytes(bytes.toByteBuf()))

    /**
     * Returns only components matching any of supplied protocols
     */
    fun filterComponents(vararg proto: Protocol): List<Pair<Protocol, ByteArray>> = components.filter { proto.contains(it.first) }

    /**
     * Returns the first found protocol value. [null] if the protocol not found
     */
    fun getComponent(proto: Protocol): ByteArray? = filterComponents(proto).firstOrNull()?.second

    /**
     * Queries the address to confirm if it contains the given protocol
     */
    fun has(proto: Protocol): Boolean = getComponent(proto) != null
    fun hasAny(vararg protos: Protocol) = protos.any { has(it) }

    /**
     * Returns [components] in a human readable form where each protocol value
     * is deserialized and represented as String
     */
    fun filterStringComponents(): List<Pair<Protocol, String?>> =
        components.map { p -> p.first to if (p.first.size == 0) null else p.first.bytesToAddress(p.second) }

    /**
     * Returns only components (String representation) matching any of supplied protocols
     */
    fun filterStringComponents(vararg proto: Protocol): List<Pair<Protocol, String?>> = filterStringComponents().filter { proto.contains(it.first) }

    /**
     * Returns the first found protocol String value representation . [null] if the protocol not found
     */
    fun getStringComponent(proto: Protocol): String? = filterStringComponents(proto).firstOrNull()?.second

    /**
     * Serializes this instance to supplied [ByteBuf]
     */
    fun writeBytes(buf: ByteBuf): ByteBuf {
        for (component in components) {
            buf.writeBytes(component.first.encoded)
            component.first.writeAddressBytes(buf, component.second)
        }
        return buf
    }

    /**
     * Returns serialized form as [ByteArray]
     */
    fun getBytes(): ByteArray = writeBytes(Unpooled.buffer()).toByteArray()

    fun toPeerIdAndAddr(): Pair<PeerId, Multiaddr> {
        if (!has(PEER_ID_PROTOCOL))
            throw IllegalArgumentException("Multiaddr has no peer id")

        return Pair(
            PeerId.fromBase58(getStringComponent(PEER_ID_PROTOCOL)!!),
            Multiaddr(components.subList(0, components.lastIndex))
        )
    }

    /**
     * Appends `/p2p/` component if absent or checks that existing and supplied ids are equal
     * @throws IllegalArgumentException if existing `/p2p/` identity doesn't match [peerId]
     */
    fun withP2P(peerId: PeerId) = withComponent(Protocol.P2P, peerId.bytes)

    /**
     * Appends new component if absent or checks that existing and supplied component values are equal
     * @throws IllegalArgumentException if existing component value doesn't match [value]
     */
    fun withComponent(protocol: Protocol, value: ByteArray): Multiaddr {
        val curVal = getComponent(protocol)
        return if (curVal != null) {
            if (!curVal.contentEquals(value)) {
                throw IllegalArgumentException("Value ($value) for $protocol doesn't match existing value in $this")
            } else {
                this
            }
        } else {
            Multiaddr(this.components + (protocol to value))
        }
    }

    /**
     * Merges components of this [Multiaddr] with [other]
     * Has the same effect as appending [other] components subsequently by [withComponent]
     * @throws IllegalArgumentException if any of `this` component value doesn't match the value for the same protocol in [other]
     */
    fun merged(other: Multiaddr) = other.components
        .fold(this) { accumulator, component ->
            accumulator.withComponent(component.first, component.second)
        }

    internal fun split(pred: (Protocol) -> Boolean): List<Multiaddr> {
        val addresses = mutableListOf<Multiaddr>()
        split(
            addresses,
            components,
            pred
        )
        return addresses
    }

    private fun split(
        accumulated: MutableList<Multiaddr>,
        remainingComponents: List<Pair<Protocol, ByteArray>>,
        pred: (Protocol) -> Boolean
    ) {
        val splitIndex = remainingComponents.indexOfLast { pred(it.first) }

        if (splitIndex > 0) {
            accumulated.add(0, Multiaddr(remainingComponents.subList(splitIndex, remainingComponents.size)))

            split(
                accumulated,
                remainingComponents.subList(0, splitIndex),
                pred
            )
        } else {
            accumulated.add(0, Multiaddr(remainingComponents))
        }
    }

    /**
     * Returns the string representation of this multiaddress
     * Note that `Multiaddress(strAddr).toString` is not always equal to `strAddr`
     * (e.g. `/ip6/::1` can be converted to `/ip6/0:0:0:0:0:0:0:1`)
     */
    override fun toString(): String = filterStringComponents().joinToString(separator = "") { p ->
        "/" + p.first.typeName + if (p.second != null) "/" + p.second else ""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Multiaddr
        return toString() == other.toString()
    }

    override fun hashCode(): Int {
        return toString().hashCode()
    }

    companion object {
        @JvmStatic
        fun fromString(addr: String): Multiaddr { // helper method for Java access
            return Multiaddr(addr)
        }

        private fun parseString(addr_: String): List<Pair<Protocol, ByteArray>> {
            val ret: MutableList<Pair<Protocol, ByteArray>> = mutableListOf()

            try {
                var addr = addr_
                while (addr.endsWith("/"))
                    addr = addr.substring(0, addr.length - 1)
                val parts = addr.split("/")
                if (parts[0].isNotEmpty()) throw IllegalArgumentException("MultiAddress must start with a /")

                var i = 1
                while (i < parts.size) {
                    val part = parts[i++]
                    val p = Protocol.getOrThrow(part)

                    val bytes = if (p.size == 0) ByteArray(0) else {
                        val component = if (p.isPath())
                            "/" + parts.subList(i, parts.size).reduce { a, b -> "$a/$b" }
                        else parts[i++]

                        if (component.isEmpty())
                            throw IllegalArgumentException("Protocol requires address, but non provided!")
                        p.addressToBytes(component)
                    }
                    ret.add(p to bytes)

                    if (p.isPath())
                        break
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed multiaddr: '$addr_", e)
            }
            return ret
        }

        private fun parseBytes(buf: ByteBuf): List<Pair<Protocol, ByteArray>> {
            val ret: MutableList<Pair<Protocol, ByteArray>> = mutableListOf()
            while (buf.isReadable) {
                val protocol = Protocol.getOrThrow(buf.readUvarint().toInt())
                ret.add(protocol to protocol.readAddressBytes(buf))
            }
            return ret
        }
    }
}
