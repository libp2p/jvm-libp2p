package io.libp2p.core.types

import io.netty.buffer.ByteBuf

fun ByteBuf.writeUvarint(value: Long): ByteBuf {
    var v = value
    while (v >= 0x80) {
        this.writeByte((v or 0x80).toInt())
        v = v shr 7
    }
    this.writeByte(v.toInt())
    return this
}

fun ByteBuf.readUvarint(): Long {
    var x: Long = 0
    var s = 0

    for (i in 0..9) {
        val b = this.readByte()
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
