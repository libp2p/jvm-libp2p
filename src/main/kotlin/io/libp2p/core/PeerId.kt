package io.libp2p.core

inline class PeerId(val b: ByteArray) {

    object companion {
        @JvmStatic
        fun fromBase58(str: String): PeerId? {
            return null
        }
    }
}