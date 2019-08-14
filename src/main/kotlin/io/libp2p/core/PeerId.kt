package io.libp2p.core

import io.libp2p.core.crypto.PubKey
import io.libp2p.core.encode.Base58
import io.libp2p.core.multiformats.Multihash
import io.libp2p.core.types.toByteArray
import io.libp2p.core.types.toByteBuf
import kotlin.random.Random

class PeerId(val b: ByteArray) {

    fun toBase58() = Base58.encode(b)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerId
        return b.contentEquals(other.b)
    }

    override fun hashCode(): Int {
        return b.contentHashCode()
    }

    override fun toString(): String {
        return toBase58()
    }

    companion object {
        @JvmStatic
        fun fromBase58(str: String): PeerId {
            return PeerId(Base58.decode(str))
        }

        @JvmStatic
        fun fromPubKey(pubKey: PubKey): PeerId {
            val mh = Multihash.digest(Multihash.Descriptor(Multihash.Digest.SHA2, 256), pubKey.bytes().toByteBuf())
            return PeerId(mh.bytes.toByteArray())
        }

        @JvmStatic
        fun random(): PeerId {
            return PeerId(Random.nextBytes(32))
        }
    }
}