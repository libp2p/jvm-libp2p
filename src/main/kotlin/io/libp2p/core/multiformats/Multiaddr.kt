package io.libp2p.core.multiformats

import io.libp2p.core.types.readUvarint
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.netty.buffer.ByteBuf

class Multiaddr(val components: Array<Pair<Protocol, ByteArray>>) {

    constructor(addr: String) : this(parseString(addr))

    constructor(bytes: ByteBuf) : this(parseBytes(bytes))
    constructor(bytes: ByteArray) : this(parseBytes(bytes.toByteBuf()))

    fun getStringComponents(): List<Pair<Protocol, String>> =
        components.map { p -> p.first to p.first.bytesToAddress(p.second.toByteBuf()) }

    override fun toString(): String = getStringComponents().joinToString { p -> "/" + p.first.typeName + "/" + p.second }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Multiaddr
        if (!components.contentEquals(other.components)) return false
        return true
    }

    override fun hashCode(): Int =  components.contentHashCode()

    companion object {
        private fun parseString(addr_: String): Array<Pair<Protocol, ByteArray>> {
            val ret: MutableList<Pair<Protocol, ByteArray>> = mutableListOf()

            var addr = addr_
            while (addr.endsWith("/"))
                addr = addr.substring(0, addr.length - 1)
            val parts = addr.split("/")
            if (parts[0].isNotEmpty()) throw IllegalStateException("MultiAddress must start with a /")

            var i = 1
            while (i < parts.size) {
                val part = parts[i++]
                val p = Protocol.getOrThrow(part)

                val bytes = if (p.size == 0) ByteArray(0) else {
                    val component = if (p.isPath())
                        "/" + parts.subList(i, parts.size).reduce { a, b -> "$a/$b" }
                    else parts[i++]

                    if (component.isEmpty())
                        throw IllegalStateException("Protocol requires address, but non provided!")
                    p.addressToBytes(component)
                }
                ret.add(p to bytes)

                if (p.isPath())
                    break
            }
            return ret.toTypedArray()
        }

        private fun parseBytes(buf: ByteBuf): Array<Pair<Protocol, ByteArray>> {
            val ret: MutableList<Pair<Protocol, ByteArray>> = mutableListOf()
            while (buf.isReadable) {
                val protocol = Protocol.getOrThrow(buf.readUvarint().toInt())
                ret.add(protocol to protocol.readAddressBytes(buf).toByteArray())
            }
            return ret.toTypedArray()
        }
    }
}