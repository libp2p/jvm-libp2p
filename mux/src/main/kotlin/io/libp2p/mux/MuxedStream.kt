package io.libp2p.mux

import java.util.Date
import io.netty.buffer.ByteBuf
/**
 * MuxedStream is a bidirectional io pipe within a connection.
 * Adapted from Go reference implementation:
 * https://github.com/libp2p/go-libp2p-core/blob/master/mux/mux.go
 */
interface MuxedStream {

    fun read(): ByteBuf

    fun write(bytes: ByteBuf): Long

    fun close(): Boolean

    fun reset()

    fun setDeadline(date: Date)

    fun setReadDeadline(date: Date)

    fun setWriteDeadline(date: Date)
}
