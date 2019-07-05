package io.libp2p.core.types

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
    return 0;
}

fun ByteArray.toProtobuf(): ByteString = ByteString.copyFrom(this)

fun ByteArray.sliceTrailing(count: Int) = slice((size - count) until size)

fun BigInteger.toBytes(numBytes: Int): ByteArray {
    val bytes = ByteArray(numBytes)
    val biBytes = toByteArray();
    val start = if (biBytes.size == numBytes + 1) 1 else 0
    val length = min(biBytes.size, numBytes)
    arraycopy(biBytes, start, bytes, numBytes - length, length)
    return bytes;
}
