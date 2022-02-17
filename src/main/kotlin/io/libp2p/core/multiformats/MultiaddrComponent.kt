package io.libp2p.core.multiformats

import io.netty.buffer.ByteBuf
import java.util.Objects

/**
 * Parsed component of [Multiaddr]
 */
class MultiaddrComponent(
    val protocol: Protocol,
    val value: ByteArray?
) {
    val stringValue by lazy { if (value != null) protocol.bytesToAddress(value) else null }

    init {
        protocol.validate(value)
    }

    fun serialize(buf: ByteBuf) {
        buf.writeBytes(protocol.encoded)
        protocol.writeAddressBytes(buf, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MultiaddrComponent) return false
        if (protocol != other.protocol) return false
        if (!value.contentEquals(other.value)) return false
        return true
    }
    override fun hashCode() = Objects.hash(protocol, value.contentHashCode())
    override fun toString() = "/${protocol.typeName}" +
        (if (stringValue != null) "/$stringValue" else "")
}
