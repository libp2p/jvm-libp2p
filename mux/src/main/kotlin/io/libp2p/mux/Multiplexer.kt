package io.libp2p.mux

/**
 * Multiplexer wraps a net.Conn with a stream multiplexing
 * implementation and returns a MuxedConn that supports opening
 * multiple streams over the underlying net.Conn
 * Adapted from Go reference implementation:
 * https://github.com/libp2p/go-libp2p-core/blob/master/mux/mux.go
 */
interface Multiplexer {

    // NewConn constructs a new connection
    fun newConn(isServer: Boolean): MuxedConn
}
