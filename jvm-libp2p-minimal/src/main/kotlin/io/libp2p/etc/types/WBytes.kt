package io.libp2p.etc.types

import com.google.protobuf.ByteString

/**
 * `ByteArray` wrapper with  `equals()`, `hashCode()` and `toString()`
 */
class WBytes(val array: ByteArray) {

    operator fun plus(other: WBytes) = (array + other.array).toWBytes()
    operator fun plus(other: ByteArray) = (array + other).toWBytes()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WBytes

        if (!array.contentEquals(other.array)) return false

        return true
    }

    override fun hashCode(): Int {
        return array.contentHashCode()
    }

    override fun toString() = array.toHex()
}

fun ByteArray.toWBytes() = WBytes(this)
fun String.toWBytes() = this.fromHex().toWBytes()
fun WBytes.toProtobuf() = this.array.toProtobuf()
fun ByteString.toWBytes() = this.toByteArray().toWBytes()
