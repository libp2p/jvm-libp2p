package io.libp2p.etc.types

import io.netty.buffer.ByteBuf
import java.lang.Integer.min

/**
 * Extends ByteBuf to add a write* method for unsigned varints, as defined in https://github.com/multiformats/unsigned-varint.
 */
fun ByteBuf.writeUvarint(value: Int): ByteBuf = writeUvarint(value.toLong())

fun ByteBuf.writeUvarint(value: Long): ByteBuf {
    if (value < 0) {
        throw IllegalArgumentException("uvarint value must be positive")
    }

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
    for (i in 0..8) {
        if (!this.isReadable) {
            // buffer contains just a fragment of uint
            readerIndex(originalReaderIndex)
            return -1
        }
        val b = this.readUnsignedByte()
        if (b < 0x80) {
            return x or (b.toLong() shl s)
        }
        x = x or (b.toLong() and 0x7f shl s)
        s += 7
    }
    throw IllegalStateException("uvarint too long")
}

fun ByteBuf.sliceMaxSize(maxSize: Int): List<ByteBuf> {
    val length = this.readableBytes()
    return when {
        length == 0 -> {
            this.release()
            listOf()
        }
        length < maxSize -> listOf(this)
        else -> {
            val ret = generateSequence(0) { it + maxSize }
                .map { off -> min(length - off, maxSize) }
                .takeWhile { size -> size > 0 }
                .map { size -> this.readSlice(size).retain() }
                .toList()
            this.release()
            ret
        }
    }
}
