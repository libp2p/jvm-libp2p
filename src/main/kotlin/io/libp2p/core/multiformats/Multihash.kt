package io.libp2p.core.multiformats

import io.libp2p.etc.types.readUvarint
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.writeUvarint
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.bouncycastle.jcajce.provider.digest.SHA3
import java.security.MessageDigest

const val MAX_HASH_LENGTH_BITS = 1024 * 1024 * 8

class InvalidMultihashException(message: String) : Exception(message)

fun MessageDigest.digest(bytes: ByteBuf): ByteBuf = Unpooled.wrappedBuffer(this.digest(bytes.toByteArray()))

/**
 * Implements Multihash spec: https://github.com/multiformats/multihash
 */
class Multihash(val bytes: ByteBuf, val desc: Descriptor, val lengthBits: Int, val value: ByteBuf) {

    @Throws(InvalidMultihashException::class)
    fun validate(): Multihash = when {
        desc.digest == Digest.Identity && lengthBits > 127 * 8 ->
            throw InvalidMultihashException("Unsupported hash size with identity digest: $lengthBits")
        lengthBits > MAX_HASH_LENGTH_BITS ->
            throw InvalidMultihashException("Maximum hash lengthBits exceeded: $lengthBits")
        else -> this
    }

    data class Descriptor(val digest: Digest, val outputSize: Int? = null)

    data class TableEntry(val code: Long, val desc: Descriptor, val hashFunc: (ByteBuf) -> ByteBuf)

    companion object {
        private val REGISTRY = mutableMapOf<Descriptor, TableEntry>()
        private val INDEX = mutableMapOf<Long, TableEntry>()

        init {
            // TODO: optimise this. We should not look up the MessageDigest every time.
            register(TableEntry(0x00, Descriptor(Digest.Identity)) {
                it
            })
            register(TableEntry(0X11, Descriptor(Digest.SHA1, 160)) {
                MessageDigest.getInstance("SHA-1").digest(it)
            })
            register(TableEntry(0X12, Descriptor(Digest.SHA2, 256)) {
                MessageDigest.getInstance("SHA-256").digest(it)
            })
            register(TableEntry(0X13, Descriptor(Digest.SHA2, 512)) {
                MessageDigest.getInstance("SHA-512").digest(it)
            })
            register(TableEntry(0XD5, Descriptor(Digest.MD5, 128)) {
                MessageDigest.getInstance("MD5").digest(it)
            })
            register(TableEntry(0X17, Descriptor(Digest.SHA3, 224)) {
                SHA3.Digest224().digest(it)
            })
            register(TableEntry(0X16, Descriptor(Digest.SHA3, 256)) {
                SHA3.Digest256().digest(it)
            })
            register(TableEntry(0X15, Descriptor(Digest.SHA3, 384)) {
                SHA3.Digest384().digest(it)
            })
            register(TableEntry(0X14, Descriptor(Digest.SHA3, 512)) {
                SHA3.Digest512().digest(it)
            })
        }

        @JvmStatic
        fun register(tableEntry: TableEntry) {
            REGISTRY[tableEntry.desc] = tableEntry
            INDEX[tableEntry.code] = tableEntry
        }

        @JvmStatic
        fun of(mh: ByteBuf): Multihash = with(mh) {
            val desc = readUvarint().let {
                forCode(it)?.desc ?: throw InvalidMultihashException("Unrecognised multihash header")
            }
            val lengthBits = readUvarint().toInt().times(8) // bits
            val value = slice()
            Multihash(this, desc, lengthBits, value)
        }

        @JvmStatic
        fun wrap(desc: Descriptor, lengthBits: Int, digest: ByteBuf, code: Long? = null): Multihash {
            val lengthBytes = lengthBits.div(8)
            val mhCode = code ?: REGISTRY[desc]?.code ?: throw InvalidMultihashException("Unrecognised multihash descriptor")
            with(Unpooled.buffer(lengthBytes + 10)) {
                writeUvarint(mhCode)
                writeUvarint(lengthBytes)
                writeBytes(digest.slice(0, lengthBytes))
                return Multihash(this, desc, lengthBits, digest)
            }
        }
        @JvmStatic
        fun digest(desc: Descriptor, content: ByteBuf, lengthBits: Int? = null): Multihash {
            val entry = REGISTRY[desc] ?: throw InvalidMultihashException("Unrecognized multihash descriptor")
            val digest = entry.hashFunc(content)
            val l: Int = when {
                lengthBits == null ->
                    // if outputSize is not defined, this is the identity function, so the lengthBits == input.
                    desc.outputSize ?: (content.readableBytes().takeIf { it <= MAX_HASH_LENGTH_BITS }?.times(8)
                        ?: throw InvalidMultihashException("Content to hash is too long"))
                desc.outputSize != null && lengthBits > desc.outputSize ->
                    throw InvalidMultihashException("Requested lengthBits longer than hash output size")
                else -> lengthBits
            }
            return wrap(desc, l, digest, entry.code)
        }

        @JvmStatic
        fun forCode(code: Long): TableEntry? = INDEX[code]
    }

    enum class Digest {
        Identity,
        SHA1,
        SHA2,
        SHA3,
        SHAKE,
        KECCAK,
        MURMUR3,
        DBL,
        MD4,
        MD5,
        BMT,
        X11,
        BLAKE2B,
        BLAKE2S,
        SKEIN256,
        SKEIN512,
        SKEIN1024
    }
}
