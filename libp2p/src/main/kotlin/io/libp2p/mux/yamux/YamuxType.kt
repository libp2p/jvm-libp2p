package io.libp2p.mux.yamux

import io.libp2p.mux.InvalidFrameMuxerException

/**
 * Contains all the permissible values for types in the <code>yamux</code> protocol.
 */
enum class YamuxType(val intValue: Int) {
    DATA(0),
    WINDOW_UPDATE(1),
    PING(2),
    GO_AWAY(3);

    companion object {
        private val intToTypeCache = values().associateBy { it.intValue }

        fun fromInt(intValue: Int): YamuxType =
            intToTypeCache[intValue] ?: throw InvalidFrameMuxerException("Invalid Yamux type value: $intValue")
    }
}
