package io.libp2p.tools

import io.netty.buffer.ByteBuf

fun ByteBuf.readAllBytesAndRelease(): ByteArray {
    val arr = ByteArray(readableBytes())
    this.readBytes(arr)
    this.release()
    return arr
}
