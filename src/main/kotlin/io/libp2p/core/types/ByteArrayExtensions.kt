package io.libp2p.core.types

import com.google.protobuf.ByteString

fun ByteArray.toHex() =
    this.joinToString(separator = "") { it.toInt().and(0xff).toString(16).padStart(2, '0') }

fun String.fromHex() =
    ByteArray(this.length / 2) { this.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

operator fun ByteArray.compareTo(other: ByteArray): Int {
    if (size != other.size) return size - other.size
    for (i in 0..size) {
        if (this[i] != other[i]) return this[i] - other[i]
    }
    return 0;
}

fun ByteArray.toProtobuf(): ByteString = ByteString.copyFrom(this)
