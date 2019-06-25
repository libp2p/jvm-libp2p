package io.libp2p.core.types

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

/**
 * Extends Netty's ByteBuf with syntactic sugar to extract a ByteArray (byte[] in Java) easily,
 * which is created by copy.
 */
fun ByteBuf.toByteArray(): ByteArray = ByteArray(this.readableBytes()).apply {
    readBytes(this)
}

/**
 * Extends Kotlin's ByteArray (byte[] in Java) with syntactic sugar to wrap it in Netty's ByteBuf easily.
 */
fun ByteArray.toByteBuf(): ByteBuf = Unpooled.wrappedBuffer(this)