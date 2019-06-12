package io.libp2p.core.multiformats

import io.libp2p.core.types.readUvarint
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

data class Multiaddr(val components: List<Pair<Protocol, ByteArray>>) {

    constructor(addr: String) : this(parseString(addr))

    constructor(bytes: ByteBuf) : this(parseBytes(bytes))
    constructor(bytes: ByteArray) : this(parseBytes(bytes.toByteBuf()))

    fun getStringComponents(): List<Pair<Protocol, String?>> =
        components.map { p -> p.first to if (p.first.size == 0) null else p.first.bytesToAddress(p.second) }

    fun writeBytes(buf: ByteBuf) : ByteBuf {
        for (component in components) {
            buf.writeBytes(component.first.encoded)
            buf.writeBytes(component.second)
        }
        return buf
    }

    fun getBytes() : ByteArray = writeBytes(Unpooled.buffer()).toByteArray()

    override fun toString(): String = getStringComponents().joinToString(separator = "")
            { p -> "/" + p.first.typeName + if (p.second != null) "/" + p.second else ""}

    companion object {
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
                ret.add(protocol to protocol.readAddressBytes(buf).toByteArray())
            }
            return ret
        }
    }
}