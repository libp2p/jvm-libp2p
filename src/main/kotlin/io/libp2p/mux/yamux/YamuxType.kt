package io.libp2p.mux.yamux

/**
 * Contains all the permissible values for flags in the <code>yamux</code> protocol.
 */
object YamuxType {
    const val DATA = 0
    const val WINDOW_UPDATE = 1
    const val PING = 2
    const val GO_AWAY = 3
}
