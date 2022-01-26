package io.libp2p.core.multiformats

import io.libp2p.core.PeerId
import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

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
data class Multiaddr(val components: List<MultiaddrComponent>) {

    /**
     * Creates instance from the string representation
     */
    constructor(addr: String) : this(parseString(addr))

    /**
     * Returns only components matching any of supplied protocols
     */
    fun filterComponents(vararg proto: Protocol): List<MultiaddrComponent> =
        components.filter { proto.contains(it.protocol) }

    /**
     * Returns the first found protocol value. [null] if the protocol not found
     */
    fun getFirstComponent(proto: Protocol): MultiaddrComponent? = filterComponents(proto).firstOrNull()

    /**
     * Queries the address to confirm if it contains the given protocol
     */
    fun has(proto: Protocol): Boolean = getFirstComponent(proto) != null
    fun hasAny(vararg protos: Protocol) = protos.any { has(it) }

    /**
     * Returns [PeerId] from either `/ipfs/` or `/p2p/` component value. `null` if none of those components exists
     */
    fun getPeerId(): PeerId? =
        components.filter { it.protocol in Protocol.PEER_ID_PROTOCOLS }.map { PeerId(it.value!!) }.firstOrNull()

    /**
     * Appends `/p2p/` component if absent or checks that existing and supplied ids are equal
     * @throws IllegalArgumentException if existing `/p2p/` identity doesn't match [peerId]
     */
    fun withP2P(peerId: PeerId) = withComponent(Protocol.P2P, peerId.bytes)

    /**
     * Appends new component if absent or checks that existing and supplied component values are equal
     * @throws IllegalArgumentException if existing component value doesn't match [value]
     */
    private fun withComponentImpl(protocol: Protocol, value: ByteArray?): Multiaddr {
        val existingComponent = getFirstComponent(protocol)
        val newComponent = MultiaddrComponent(protocol, value)
        return if (existingComponent != null) {
            if (!existingComponent.value.contentEquals(value)) {
                throw IllegalArgumentException("Value (${newComponent.stringValue}) for $protocol doesn't match existing value in $this")
            } else {
                this
            }
        } else {
            Multiaddr(this.components + newComponent)
        }
    }

    fun withComponent(protocol: Protocol, value: ByteArray): Multiaddr =
        withComponentImpl(protocol, value)

    fun withComponent(protocol: Protocol, stringValue: String): Multiaddr =
        withComponentImpl(protocol, protocol.addressToBytes(stringValue))

    fun withComponent(protocol: Protocol): Multiaddr =
        withComponentImpl(protocol, null)

    /**
     * Returns [Multiaddr] with concatenated components of `this` and [other] `Multiaddr`
     * No cross component checks or merge is performed
     */
    fun concatenated(other: Multiaddr) = Multiaddr(this.components + other.components)

    /**
     * Merges components of this [Multiaddr] with [other]
     * Has the same effect as appending [other] components subsequently by [withComponent]
     * @throws IllegalArgumentException if any of `this` component value doesn't match the value for the same protocol in [other]
     */
    fun merged(other: Multiaddr) = other.components
        .fold(this) { accumulator, component ->
            accumulator.withComponentImpl(component.protocol, component.value)
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
        remainingComponents: List<MultiaddrComponent>,
        pred: (Protocol) -> Boolean
    ) {
        val splitIndex = remainingComponents.indexOfLast { pred(it.protocol) }

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
     * Serializes this instance to supplied [ByteBuf]
     */
    fun serializeToBuf(buf: ByteBuf): ByteBuf {
        for (component in components) {
            component.serialize(buf)
        }
        return buf
    }

    /**
     * Returns serialized form as [ByteArray]
     */
    fun serialize(): ByteArray = serializeToBuf(Unpooled.buffer()).toByteArray()

    /**
     * Returns the string representation of this multiaddress
     * Note that `Multiaddress(strAddr).toString` is not always equal to `strAddr`
     * (e.g. `/ip6/::1` can be converted to `/ip6/0:0:0:0:0:0:0:1`)
     */
    override fun toString(): String = components.joinToString(separator = "")

    companion object {

        @JvmStatic
        fun fromString(addr: String) = Multiaddr(parseString(addr))

        @JvmStatic
        fun deserialize(bytes: ByteArray) = Multiaddr(parseBytes(bytes.toByteBuf()))

        @JvmStatic
        fun deserializeFromBuf(bytes: ByteBuf) = Multiaddr(parseBytes(bytes))

        @JvmStatic
        fun empty() = Multiaddr(emptyList())

        private fun parseString(addr: String): List<MultiaddrComponent> {
            val ret: MutableList<MultiaddrComponent> = mutableListOf()

            try {
                val parts = addr.trimEnd('/').split("/")
                if (parts[0].isNotEmpty()) throw IllegalArgumentException("MultiAddress must start with a /")

                var i = 1
                while (i < parts.size) {
                    val part = parts[i++]
                    val p = Protocol.getOrThrow(part)

                    val bytes = if (!p.hasValue) {
                        null
                    } else {
                        val component = if (p.isPath) {
                            val remainingParts = parts.drop(i)
                            i = parts.size
                            "/" + remainingParts.joinToString("/")
                        } else {
                            parts[i++]
                        }
                        p.addressToBytes(component)
                    }
                    ret += MultiaddrComponent(p, bytes)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed multiaddr: '$addr", e)
            }
            return ret
        }

        private fun parseBytes(buf: ByteBuf): List<MultiaddrComponent> {
            val ret: MutableList<MultiaddrComponent> = mutableListOf()
            while (buf.isReadable) {
                val protocol = Protocol.getOrThrow(buf.readUvarint().toInt())
                ret += MultiaddrComponent(protocol, protocol.readAddressBytes(buf))
            }
            return ret
        }
    }
}
