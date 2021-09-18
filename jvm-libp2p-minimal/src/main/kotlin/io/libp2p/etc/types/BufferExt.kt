package io.libp2p.etc.types

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlin.math.min

/**
 * Extends Netty's ByteBuf with syntactic sugar to extract a ByteArray (byte[] in Java) easily,
 * which is created by copy.
 */
fun ByteBuf.toByteArray(): ByteArray = ByteArray(this.readableBytes()).apply {
    slice().readBytes(this)
}

fun ByteBuf.toByteArray(from: Int = 0, to: Int = Int.MAX_VALUE): ByteArray {
    val toFinal = min(readableBytes(), to)
    val len = toFinal - from
    val ret = ByteArray(len)
    this.slice(from, toFinal - from).readBytes(ret)
    return ret
}

/**
 * Extends Kotlin's ByteArray (byte[] in Java) with syntactic sugar to wrap it in Netty's ByteBuf easily.
 */
fun ByteArray.toByteBuf(): ByteBuf = Unpooled.wrappedBuffer(this)
