package io.libp2p.mux.yamux

import io.libp2p.mux.InvalidFrameMuxerException

/**
 * Contains all the permissible values for flags in the <code>yamux</code> protocol.
 */
enum class YamuxFlag(val intFlag: Int) {
    SYN(1),
    ACK(2),
    FIN(4),
    RST(8);

    val asSet: Set<YamuxFlag> = setOf(this)

    companion object {
        val NONE = emptySet<YamuxFlag>()

        private val validFlagCombinations = mapOf(
            0 to emptySet(),
            SYN.intFlag to SYN.asSet,
            ACK.intFlag to ACK.asSet,
            FIN.intFlag to FIN.asSet,
            RST.intFlag to RST.asSet,
        )

        fun fromInt(flags: Int) =
            validFlagCombinations[flags] ?: throw InvalidFrameMuxerException("Invalid Yamux flags value: $flags")

        fun Set<YamuxFlag>.toInt() = this
            .fold(0) { acc, flag -> acc or flag.intFlag}
            .also { require(it in validFlagCombinations) { "Invalid Yamux flags combination: $this" } }
    }
}
