package io.libp2p.mux

/**
 * MuxedConn represents a connection to a remote peer that has been
 * extended to support stream multiplexing.
 * Adapted from Go reference implementation:
 * https://github.com/libp2p/go-libp2p-core/blob/master/mux/mux.go
 */
interface MuxedConn {

    // Close closes the stream muxer and the the underlying net.Conn.
    fun close(): Boolean

    // IsClosed returns whether a connection is fully closed, so it can
    // be garbage collected.
    fun isClosed(): Boolean

    // OpenStream creates a new stream.
    fun openStream(): MuxedStream

    // AcceptStream accepts a stream opened by the other side.
    fun acceptStream(): MuxedStream
}
