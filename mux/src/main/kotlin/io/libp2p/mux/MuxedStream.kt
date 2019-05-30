package io.libp2p.mux

import java.util.Date
import org.apache.tuweni.bytes.Bytes
/**
 * MuxedStream is a bidirectional io pipe within a connection.
 * Adapted from Go reference implementation:
 * https://github.com/libp2p/go-libp2p-core/blob/master/mux/mux.go
 */
interface MuxedStream {

    fun read(): Bytes

    fun write(bytes: Bytes): Long

    fun close(): Boolean

    fun reset()

    fun setDeadline(date: Date)

    fun setReadDeadline(date: Date)

    fun setWriteDeadcline(date: Date)
}
