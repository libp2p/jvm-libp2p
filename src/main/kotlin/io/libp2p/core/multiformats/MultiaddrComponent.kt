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
        validate()
    }

    fun serialize(buf: ByteBuf) {
        buf.writeBytes(protocol.encoded)
        if (value != null) {
            protocol.writeAddressBytes(buf, value)
        }
    }

    private fun validate() {
        require((protocol.hasValue && value != null) || (!protocol.hasValue && value === null)) {
            "Value presence ($value) doesn't match protocol ($protocol)"
        }
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