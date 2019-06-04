package io.libp2p.core.types

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

fun ByteBuf.toByteArray(): ByteArray = ByteArray(this.readableBytes()).let {
    readBytes(it)
    it
}

fun ByteArray.toByteBuf(): ByteBuf = Unpooled.wrappedBuffer(this)