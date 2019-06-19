package io.libp2p.core.types

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlin.math.min

fun ByteBuf.toByteArray(): ByteArray = ByteArray(this.readableBytes()).let {
    slice().readBytes(it)
    it
}

fun ByteBuf.toByteArray(from: Int = 0, to: Int = Int.MAX_VALUE): ByteArray {
    val toFinal = min(readableBytes(), to)
    val len = toFinal - from
    val ret = ByteArray(len)
    this.slice(from, toFinal - from).readBytes(ret)
    return ret
}


fun ByteArray.toByteBuf(): ByteBuf = Unpooled.wrappedBuffer(this)