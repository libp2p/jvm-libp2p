package io.libp2p.core

inline class PeerId(val b: ByteArray) {

    companion object {
        @JvmStatic
        fun fromBase58(str: String): PeerId? {
            return null
        }

        @JvmStatic
        fun random(): PeerId {
            return PeerId(ByteArray(0))
        }
    }
}