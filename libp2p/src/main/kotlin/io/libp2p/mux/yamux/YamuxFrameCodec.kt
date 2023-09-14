package io.libp2p.mux.yamux

import io.libp2p.core.ProtocolViolationException
import io.libp2p.etc.util.netty.mux.MuxId
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageCodec

const val DEFAULT_MAX_YAMUX_FRAME_DATA_LENGTH = 1 shl 20

/**
 * A Netty codec implementation that converts [YamuxFrame] instances to [ByteBuf] and vice-versa.
 */
class YamuxFrameCodec(
    val maxFrameDataLength: Int = DEFAULT_MAX_YAMUX_FRAME_DATA_LENGTH
) : ByteToMessageCodec<YamuxFrame>() {

    /**
     * Encodes the given yamux frame into bytes and writes them into the output list.
     * @see [https://github.com/hashicorp/yamux/blob/master/spec.md]
     * @param ctx the context.
     * @param msg the yamux frame.
     * @param out the list to write the bytes to.
     */
    override fun encode(ctx: ChannelHandlerContext, msg: YamuxFrame, out: ByteBuf) {
        out.writeByte(0) // version
        out.writeByte(msg.type)
        out.writeShort(msg.flags)
        out.writeInt(msg.id.id.toInt())
        out.writeInt(msg.data?.readableBytes() ?: msg.length.toInt())
        out.writeBytes(msg.data ?: Unpooled.EMPTY_BUFFER)
    }

    /**
     * Decodes the bytes in the given byte buffer and constructs a [YamuxFrame] that is written into
     * the output list.
     * @param ctx the context.
     * @param msg the byte buffer.
     * @param out the list to write the extracted frame to.
     */
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        while (msg.isReadable) {
            if (msg.readableBytes() < 12) {
                return
            }
            val readerIndex = msg.readerIndex()
            msg.readByte(); // version always 0
            val type = msg.readUnsignedByte()
            val flags = msg.readUnsignedShort()
            val streamId = msg.readUnsignedInt()
            val length = msg.readUnsignedInt()
            val yamuxId = YamuxId(ctx.channel().id(), streamId)
            if (type.toInt() != YamuxType.DATA) {
                val yamuxFrame = YamuxFrame(
                    yamuxId,
                    type.toInt(),
                    flags,
                    length
                )
                out.add(yamuxFrame)
                continue
            }
            if (length > maxFrameDataLength) {
                msg.skipBytes(msg.readableBytes())
                throw ProtocolViolationException("Yamux frame is too large: $length")
            }
            if (msg.readableBytes() < length) {
                // not enough data to read the frame content
                // will wait for more ...
                msg.readerIndex(readerIndex)
                return
            }
            val data = msg.readSlice(length.toInt())
            data.retain() // MessageToMessageCodec releases original buffer, but it needs to be relayed
            val yamuxFrame = YamuxFrame(
                yamuxId,
                type.toInt(),
                flags,
                length,
                data
            )
            out.add(yamuxFrame)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // notify higher level handlers on the error
        ctx.fireExceptionCaught(cause)
        // exceptions in [decode] are very likely unrecoverable so just close the connection
        ctx.close()
    }
}
