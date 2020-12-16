package io.libp2p.etc.util.netty.protobuf

import io.netty.handler.codec.ByteToMessageDecoder
import kotlin.Throws
import io.netty.channel.ChannelHandlerContext
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.CorruptedFrameException
import java.lang.Exception

/**
 * This class is a modified version of netty's ProtobufVariant32FrameDecoder that enforces limits on the
 * size of the protobuf data.
 * @see [ProtobufVarint32FrameDecoder](https://github.com/netty/netty/blob/e5951d46fc89db507ba7d2968d2ede26378f0b04/codec/src/main/java/io/netty/handler/codec/protobuf/ProtobufVarint32FrameDecoder.java)
 *
 * A decoder that splits the received {@link ByteBuf}s dynamically by the
 * value of the Google Protocol Buffers
 * <a href="https://developers.google.com/protocol-buffers/docs/encoding#varints">Base
 * 128 Varints</a> integer length field in the message. For example:
 * <pre>
 * BEFORE DECODE (302 bytes)       AFTER DECODE (300 bytes)
 * +--------+---------------+      +---------------+
 * | Length | Protobuf Data |----->| Protobuf Data |
 * | 0xAC02 |  (300 bytes)  |      |  (300 bytes)  |
 * +--------+---------------+      +---------------+
 * </pre>
 *
 * @see CodedInputStream
 */
internal class LimitedProtobufVarint32FrameDecoder(private val maxFrameLength: Long) : ByteToMessageDecoder() {

    @Throws(Exception::class)
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        msg.markReaderIndex()
        val preIndex = msg.readerIndex()
        val length = readRawVarint32(msg)
        if (preIndex == msg.readerIndex()) {
            return
        }
        if (length < 0) {
            throw CorruptedFrameException("negative length: $length")
        }
        if (msg.readableBytes() < length) {
            msg.resetReaderIndex()
        } else {
            out.add(msg.readRetainedSlice(length))
        }
    }

    companion object {
        /**
         * Reads variable length 32bit int from buffer
         *
         * @return decoded int if buffers readerIndex has been forwarded else nonsense value
         */
        private fun readRawVarint32(buffer: ByteBuf): Int {
            if (!buffer.isReadable) {
                return 0
            }
            buffer.markReaderIndex()
            var tmp:Int = buffer.readByte().toInt()
            return if (tmp >= 0) {
                tmp.toInt()
            } else {
                var result: Int = tmp and 127
                if (!buffer.isReadable) {
                    buffer.resetReaderIndex()
                    return 0
                }
                if (buffer.readByte().toInt().also { tmp = it } >= 0) {
                    result = result or (tmp shl 7)
                } else {
                    result = result or (tmp and 127 shl 7)
                    if (!buffer.isReadable) {
                        buffer.resetReaderIndex()
                        return 0
                    }
                    if (buffer.readByte().toInt().also { tmp = it } >= 0) {
                        result = result or (tmp shl 14)
                    } else {
                        result = result or (tmp and 127 shl 14)
                        if (!buffer.isReadable) {
                            buffer.resetReaderIndex()
                            return 0
                        }
                        if (buffer.readByte().toInt().also { tmp = it } >= 0) {
                            result = result or (tmp shl 21)
                        } else {
                            result = result or (tmp and 127 shl 21)
                            if (!buffer.isReadable) {
                                buffer.resetReaderIndex()
                                return 0
                            }
                            result = result or (buffer.readByte().toInt().also { tmp = it } shl 28)
                            if (tmp < 0) {
                                throw CorruptedFrameException("malformed varint.")
                            }
                        }
                    }
                }
                result
            }
        }
    }
}