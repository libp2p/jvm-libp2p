package io.libp2p.etc.util.netty.protobuf

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.TooLongFrameException
import java.lang.Exception
import kotlin.Throws

/**
 * This class is a modified version of netty's `ProtobufVariant32FrameDecoder` that enforces limits on the
 * size of the protobuf data. Limit functionality is based on netty's `LengthFieldBasedFrameDecoder`.
 *
 * A decoder that splits the received [ByteBuf]s dynamically by the
 * value of the Google Protocol Buffers
 * [Base 128 Varints](https://developers.google.com/protocol-buffers/docs/encoding#varints)
 * integer length field in the message. For example:
 * ```
 * BEFORE DECODE (302 bytes)       AFTER DECODE (300 bytes)
 * +--------+---------------+      +---------------+
 * | Length | Protobuf Data |----->| Protobuf Data |
 * | 0xAC02 |  (300 bytes)  |      |  (300 bytes)  |
 * +--------+---------------+      +---------------+
 * ```
 *
 * @see [ProtobufVarint32FrameDecoder](https://github.com/netty/netty/blob/e5951d46fc89db507ba7d2968d2ede26378f0b04/codec/src/main/java/io/netty/handler/codec/protobuf/ProtobufVarint32FrameDecoder.java)
 * @see [LengthFieldBasedFrameDecoder] (https://github.com/netty/netty/blob/8ea0d8f41ac5b0b09229a53690cad7febb4332ef/codec/src/main/java/io/netty/handler/codec/LengthFieldBasedFrameDecoder.java)
 * @see CodedInputStream
 */
internal class LimitedProtobufVarint32FrameDecoder(private val maxDataLength: Int) : ByteToMessageDecoder() {
    private var discardingTooLongFrame = false
    private var tooLongFrameLength: Int = 0
    private var bytesToDiscard: Int = 0

    @Throws(Exception::class)
    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        if (discardingTooLongFrame) {
            discardFrame(msg)
        }

        msg.markReaderIndex()
        val preIndex = msg.readerIndex()
        val length = readRawVarint32(msg)
        if (preIndex == msg.readerIndex()) {
            return
        }

        // Validate length
        if (length < 0) {
            throw CorruptedFrameException("negative length: $length")
        }
        if (length > maxDataLength) {
            handleFrameExceedingMaxLength(msg, length)
            return
        }

        if (msg.readableBytes() < length) {
            msg.resetReaderIndex()
        } else {
            out.add(msg.readRetainedSlice(length))
        }
    }

    private fun handleFrameExceedingMaxLength(msg: ByteBuf, frameLength: Int) {
        val discard = frameLength - msg.readableBytes()
        tooLongFrameLength = frameLength
        if (discard < 0) {
            // buffer contains more bytes then the frameLength so we can discard all now
            msg.skipBytes(frameLength)
        } else {
            // Enter the discard mode and discard everything received so far.
            discardingTooLongFrame = true
            bytesToDiscard = discard
            msg.skipBytes(msg.readableBytes())
        }
        failIfNecessary(true)
    }

    private fun discardFrame(msg: ByteBuf) {
        var bytesToDiscard = bytesToDiscard
        val localBytesToDiscard = Math.min(bytesToDiscard, msg.readableBytes())
        msg.skipBytes(localBytesToDiscard)
        bytesToDiscard -= localBytesToDiscard
        this.bytesToDiscard = bytesToDiscard
        failIfNecessary(false)
    }

    private fun failIfNecessary(firstDetectionOfTooLongFrame: Boolean) {
        val tooLongFrameLength = tooLongFrameLength
        if (bytesToDiscard == 0) {
            // Reset to the initial state.
            this.tooLongFrameLength = 0
            discardingTooLongFrame = false
        }
        // Tell the handlers that the frame was too large.
        if (firstDetectionOfTooLongFrame) {
            fail(tooLongFrameLength)
        }
    }

    private fun fail(frameLength: Int) {
        if (frameLength > 0) {
            throw TooLongFrameException("Adjusted frame length exceeds $maxDataLength: $frameLength - discarded")
        } else {
            throw TooLongFrameException("Adjusted frame length exceeds $maxDataLength - discarding")
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
            var tmp: Int = buffer.readByte().toInt()
            return if (tmp >= 0) {
                tmp
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
