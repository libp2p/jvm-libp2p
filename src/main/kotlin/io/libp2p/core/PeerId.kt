package io.libp2p.core

import io.libp2p.core.crypto.PubKey

inline class PeerId(val b: ByteArray) {

    companion object {
        @JvmStatic
        fun fromBase58(str: String): PeerId {
            return PeerId(ByteArray(32))
        }

        @JvmStatic
        fun fromPubKey(pubKey: PubKey): PeerId {
            return PeerId(ByteArray(32))
        }

        @JvmStatic
        fun random(): PeerId {
            return PeerId(ByteArray(0))
        }
    }
}