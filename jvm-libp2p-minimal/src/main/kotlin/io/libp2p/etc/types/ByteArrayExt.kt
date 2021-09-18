package io.libp2p.etc.types

import com.google.protobuf.ByteString
import java.lang.Math.min
import java.lang.System.arraycopy
import java.math.BigInteger

fun ByteArray.toHex() =
    this.joinToString(separator = "") { it.toInt().and(0xff).toString(16).padStart(2, '0') }

fun String.fromHex() =
    ByteArray(this.length / 2) { this.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

operator fun ByteArray.compareTo(other: ByteArray): Int {
    if (size != other.size) return size - other.size
    for (i in 0 until size) {
        if (this[i] != other[i]) return this[i].toInt().and(0xFF) - other[i].toInt().and(0xFF)
    }
    return 0
}

fun ByteArray.toProtobuf(): ByteString = ByteString.copyFrom(this)

fun ByteArray.sliceTrailing(count: Int) = slice((size - count) until size)

fun BigInteger.toBytes(numBytes: Int): ByteArray {
    val bytes = ByteArray(numBytes)
    val biBytes = toByteArray()
    val start = if (biBytes.size == numBytes + 1) 1 else 0
    val length = min(biBytes.size, numBytes)
    arraycopy(biBytes, start, bytes, numBytes - length, length)
    return bytes
}

fun ByteArray.toUShortBigEndian(): Int {
    if (size != 2) throw IllegalArgumentException("Size $size != 2")
    return (this[0].toInt() and 0xFF shl 8) or
        (this[1].toInt() and 0xFF)
}

fun ByteArray.toIntBigEndian(): Int {
    if (size != 4) throw IllegalArgumentException("Size $size != 4")
    return (this[0].toInt() and 0xFF shl 24) or
        (this[1].toInt() and 0xFF shl 16) or
        (this[2].toInt() and 0xFF shl 8) or
        (this[3].toInt() and 0xFF)
}

fun ByteArray.toLongBigEndian(): Long {
    if (size != 8) throw IllegalArgumentException("Size $size != 8")
    return (this[0].toLong() and 0xFF shl 56) or
        (this[1].toLong() and 0xFF shl 48) or
        (this[2].toLong() and 0xFF shl 40) or
        (this[3].toLong() and 0xFF shl 32) or
        (this[4].toLong() and 0xFF shl 24) or
        (this[5].toLong() and 0xFF shl 16) or
        (this[6].toLong() and 0xFF shl 8) or
        (this[7].toLong() and 0xFF)
}

fun Long.toBytesBigEndian() =
    ByteArray(8) { i -> (this shr ((7 - i) * 8)).toByte() }

fun Int.toBytesBigEndian() =
    ByteArray(4) { i -> (this shr ((3 - i) * 8)).toByte() }

fun Int.uShortToBytesBigEndian() =
    ByteArray(2) { i -> (this shr ((1 - i) * 8)).toByte() }

/**
 * Extends ByteBuf to add a read* method for unsigned varints, as defined in https://github.com/multiformats/unsigned-varint.
 */
@kotlin.ExperimentalUnsignedTypes
fun ByteArray.readUvarint(): Pair<Long, ByteArray>? {
    var x: Long = 0
    var s = 0

    var index = 0
    var result: Long? = null
    for (i in 0..9) {
        val b = this.get(index++).toUByte().toShort()
        if (b < 0x80) {
            if (i == 9 && b > 1) {
                return null
            }
            result = x or (b.toLong() shl s)
            break
        }
        x = x or (b.toLong() and 0x7f shl s)
        s += 7
    }

    if (result != null && result <= size) {
        return Pair(result, slice(IntRange(index, size - 1)).toByteArray())
    }

    return null
}
