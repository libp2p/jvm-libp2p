package io.libp2p.core

import io.libp2p.core.crypto.PubKey
import io.libp2p.core.crypto.marshalPublicKey
import io.libp2p.core.multiformats.Multihash
import io.libp2p.etc.encode.Base58
import io.libp2p.etc.types.fromHex
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toHex
import kotlin.random.Random

/**
 * Represents the peer identity which is basically derived from the peer public key
 * @property bytes The peer id bytes which size should be  >= 32 and <= 50
 */
class PeerId(val bytes: ByteArray) {

    init {
        if (bytes.size < 32 || bytes.size > 50) throw IllegalArgumentException("Invalid peerId length: ${bytes.size}")
    }

    /**
     * The common [PeerId] string representation, which is just base58 of PeerId bytes
     */
    fun toBase58() = Base58.encode(bytes)

    /**
     * PeerId as Hex string
     */
    fun toHex() = bytes.toHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PeerId
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    override fun toString(): String {
        return toBase58()
    }

    companion object {
        /**
         * Creates [PeerId] from common base58 string representation
         */
        @JvmStatic
        fun fromBase58(str: String): PeerId {
            return PeerId(Base58.decode(str))
        }

        /**
         * Creates [PeerId] from Hex string representation
         */
        @JvmStatic
        fun fromHex(str: String): PeerId {
            return PeerId(str.fromHex())
        }

        /**
         * Creates [PeerId] from the Peer's public key
         */
        @JvmStatic
        fun fromPubKey(pubKey: PubKey): PeerId {
            val pubKeyBytes = marshalPublicKey(pubKey)
            val descriptor = when {
                pubKeyBytes.size <= 42 -> Multihash.Descriptor(Multihash.Digest.Identity)
                else -> Multihash.Descriptor(Multihash.Digest.SHA2, 256)
            }

            val mh = Multihash.digest(descriptor, pubKeyBytes.toByteBuf())
            return PeerId(mh.bytes.toByteArray())
        }

        /**
         * Generates random [PeerId].
         * Useful for testing purposes only since doesn't generate any private keys
         */
        @JvmStatic
        fun random(): PeerId {
            return PeerId(Random.nextBytes(32))
        }
    }
}
