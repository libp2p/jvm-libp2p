package io.libp2p.mux.yamux

/**
 * Contains all the permissible values for flags in the <code>yamux</code> protocol.
 */
object YamuxFlags {
    const val SYN = 1
    const val ACK = 2
    const val FIN = 4
    const val RST = 8
}
