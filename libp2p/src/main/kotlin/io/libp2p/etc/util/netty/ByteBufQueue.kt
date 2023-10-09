package io.libp2p.etc.util.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled

class ByteBufQueue {
    private val data: MutableList<ByteBuf> = mutableListOf()

    fun push(buf: ByteBuf) {
        data += buf
    }

    fun take(maxLength: Int): ByteBuf {
        val wholeBuffers = mutableListOf<ByteBuf>()
        var size = 0
        while (data.isNotEmpty()) {
            val bufLen = data.first().readableBytes()
            if (size + bufLen > maxLength) break
            size += bufLen
            wholeBuffers += data.removeFirst()
            if (size == maxLength) break
        }

        val partialBufferSlice =
            when {
                data.isEmpty() -> null
                size == maxLength -> null
                else -> data.first()
            }
                ?.let { buf ->
                    val remainingBytes = maxLength - size
                    buf.readSlice(remainingBytes).retain()
                }

        val allBuffers = wholeBuffers + listOfNotNull(partialBufferSlice)
        return Unpooled.wrappedBuffer(*allBuffers.toTypedArray())
    }

    fun dispose() {
        data.forEach { it.release() }
    }

    fun readableBytes(): Int = data.sumOf { it.readableBytes() }
}
