package io.libp2p.etc.types

import io.netty.buffer.ByteBuf

/**
 * Extends ByteBuf to add a write* method for unsigned varints, as defined in https://github.com/multiformats/unsigned-varint.
 */
fun ByteBuf.writeUvarint(value: Int): ByteBuf = writeUvarint(value.toLong())

fun ByteBuf.writeUvarint(value: Long): ByteBuf {
    var v = value
    while (v >= 0x80) {
        this.writeByte((v or 0x80).toInt())
        v = v shr 7
    }
    this.writeByte(v.toInt())
    return this
}

/**
 * Extends ByteBuf to add a read* method for unsigned varints, as defined in https://github.com/multiformats/unsigned-varint.
 *
 * If the buffer doesn't contain enough bytes to read varint then -1 is returned and the
 * buffer reader index remains on the original position
 */
fun ByteBuf.readUvarint(): Long {
    var x: Long = 0
    var s = 0

    val originalReaderIndex = readerIndex()
    for (i in 0..9) {
        if (!this.isReadable) {
            // buffer contains just a fragment of uint
            readerIndex(originalReaderIndex)
            return -1
        }
        val b = this.readUnsignedByte()
        if (b < 0x80) {
            if (i == 9 && b > 1) {
                throw IllegalStateException("Overflow reading uvarint")
            }
            return x or (b.toLong() shl s)
        }
        x = x or (b.toLong() and 0x7f shl s)
        s += 7
    }
    throw IllegalStateException("uvarint too long")
}
