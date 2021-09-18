package io.libp2p.etc.util.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder
import kotlin.math.min

/**
 * Splits large outbound buffers onto smaller chunks of specified max size
 */
class SplitEncoder(val maxSize: Int) : MessageToMessageEncoder<ByteBuf>() {
    override fun encode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        var startIdx = msg.readerIndex()
        while (startIdx < msg.writerIndex()) {
            val endIndex = min(startIdx + maxSize, msg.writerIndex())
            out += msg.retainedSlice(startIdx, endIndex - startIdx)
            startIdx += maxSize
        }
    }
}
