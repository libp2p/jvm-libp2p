package io.libp2p.etc.util.netty

import com.google.protobuf.CodedOutputStream
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled.wrappedBuffer
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.CorruptedFrameException
import io.netty.handler.codec.TooLongFrameException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random

class LimitedProtobufVarint32FrameDecoderTest {

    private val rand: Random = Random(1)
    private val maxDataSize = 300
    private val ch: EmbeddedChannel = EmbeddedChannel(LimitedProtobufVarint32FrameDecoder(maxDataSize))
    private val byteBufs: MutableList<ByteBuf> = mutableListOf()

    @AfterEach
    fun tearDown() {
        for (byteBuf in byteBufs) {
            byteBuf.release()
        }
        byteBufs.clear()
    }

    @Test
    fun smallMsg_deliveredInChunks() {
        val msg = byteArrayOf(4, 1, 2, 3, 4)

        // Deliver message in partial chunks
        assertThat(ch.writeInbound(wrappedBuffer(msg, 0, 1))).isFalse()
        assertThat(readInboundByteBuf()).isNull()
        assertThat(ch.writeInbound(wrappedBuffer(msg, 1, 2))).isFalse()
        assertThat(readInboundByteBuf()).isNull()
        assertThat(ch.writeInbound(wrappedBuffer(msg, 3, msg.size - 3))).isTrue()

        val expected: ByteBuf = toByteBuf(byteArrayOf(1, 2, 3, 4))
        val actual = readInboundByteBuf()
        assertThat(actual).isEqualTo(expected)

        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun smallMsg_deliveredAtOnce() {
        val msg = byteArrayOf(4, 1, 2, 3, 4)
        assertThat(ch.writeInbound(wrappedBuffer(msg, 0, msg.size))).isTrue()

        val expected: ByteBuf = toByteBuf(byteArrayOf(1, 2, 3, 4))
        val actual = readInboundByteBuf()
        assertThat(actual).isEqualTo(expected)

        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun msgExceedsLimit_deliveredAtOnce() {
        val dataSize = maxDataSize + 1
        val msg = createRandomFramedData(dataSize)

        // Reading msg should throw and not output anything
        assertThatThrownBy({ ch.writeInbound(wrappedBuffer(msg, 0, msg.size)) })
            .isInstanceOf(TooLongFrameException::class.java)
            .hasMessageContaining("Adjusted frame length exceeds $maxDataSize: $dataSize")
        assertThat(readInboundByteBuf()).isNull()

        // Check that subsequent messages are read as expected
        val data = createRandomData(maxDataSize - 1)
        val msg2 = createFramedData(data)
        ch.writeInbound(wrappedBuffer(msg2, 0, msg2.size))
        val actual = readInboundByteBuf()
        assertThat(actual?.toByteArray()).isEqualTo(data)

        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun msgExceedsLimit_deliveredInChunks() {
        val dataSize = maxDataSize + 1
        val msg = createRandomFramedData(dataSize)

        // Reading msg should throw and not output anything
        assertThat(ch.writeInbound(wrappedBuffer(msg, 0, 1))).isFalse()
        assertThat(readInboundByteBuf()).isNull()
        assertThatThrownBy({ ch.writeInbound(wrappedBuffer(msg, 1, 1)) })
            .isInstanceOf(TooLongFrameException::class.java)
            .hasMessageContaining("Adjusted frame length exceeds $maxDataSize: $dataSize")
        assertThat(readInboundByteBuf()).isNull()
        assertThat(ch.writeInbound(wrappedBuffer(msg, 2, 1))).isFalse()
        assertThat(readInboundByteBuf()).isNull()
        assertThat(ch.writeInbound(wrappedBuffer(msg, 3, 10))).isFalse()
        assertThat(readInboundByteBuf()).isNull()
        // Read the rest
        assertThat(ch.writeInbound(wrappedBuffer(msg, 13, msg.size - 13))).isFalse()
        assertThat(readInboundByteBuf()).isNull()

        // Check that subsequent messages are read as expected
        val data = createRandomData(maxDataSize - 1)
        val msg2 = createFramedData(data)
        ch.writeInbound(wrappedBuffer(msg2, 0, msg2.size))
        val actual = readInboundByteBuf()
        assertThat(actual?.toByteArray()).isEqualTo(data)

        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun msgExceedsLimit_concatenatedWithValidMessage() {
        val tooLargeDataSize = maxDataSize + 1
        val tooLargeMsg = createRandomFramedData(tooLargeDataSize)
        val validData = createRandomData(maxDataSize - 1)
        val validMsg = createFramedData(validData)

        val concatenated = wrappedBuffer(tooLargeMsg + validMsg)

        // Read concatenated message
        assertThatThrownBy({ ch.writeInbound(concatenated) })
            .isInstanceOf(TooLongFrameException::class.java)
            .hasMessageContaining("Adjusted frame length exceeds $maxDataSize: $tooLargeDataSize")
        ch.close()

        // Despite the first message caused exception the second one should still be decoded finally
        assertThat(readInboundByteBuf()?.toByteArray()).isEqualTo(validData)
        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun msgExceedsLimit_concatenatedWithValidMessage_splitFirstMsg() {
        val tooLargeDataSize = maxDataSize + 1
        val tooLargeMsg = createRandomFramedData(tooLargeDataSize)
        val validData = createRandomData(maxDataSize - 1)
        val validMsg = createFramedData(validData)

        val concatenated = wrappedBuffer(tooLargeMsg + validMsg)
        concatenated.retain()

        // Read concatenated message
        val msgSizePrefix = concatenated.slice(0, 2)
        assertThatThrownBy({ ch.writeInbound(msgSizePrefix) })
            .isInstanceOf(TooLongFrameException::class.java)
            .hasMessageContaining("Adjusted frame length exceeds $maxDataSize: $tooLargeDataSize")
        assertThat(readInboundByteBuf()).isNull()
        assertThat(msgSizePrefix.readerIndex()).isEqualTo(2)

        // Next read should bypass bad message and output valid message
        ch.writeInbound(concatenated.slice(2, concatenated.readableBytes() - 2))
        assertThat(readInboundByteBuf()?.toByteArray()).isEqualTo(validData)

        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun largeMessageAtLimit() {
        val data = createRandomData(maxDataSize)
        val msg = createFramedData(data)
        assertThat(ch.writeInbound(wrappedBuffer(msg, 0, msg.size))).isTrue()

        val actual = readInboundByteBuf()
        assertThat(actual?.toByteArray()).isEqualTo(data)

        assertThat(ch.finish()).isFalse()
    }

    @Test
    fun invalidVarIntPrefix() {
        val msg = byteArrayOf(-1, -1, -1, -1, -1, -1)

        // Reading msg should throw and not output anything
        assertThatThrownBy({ ch.writeInbound(wrappedBuffer(msg, 0, msg.size)) })
            .isInstanceOf(CorruptedFrameException::class.java)
        assertThat(readInboundByteBuf()).isNull()

        ch.finish()
    }

    private fun createRandomData(size: Int): ByteArray {
        val bytes = ByteArray(size)
        rand.nextBytes(bytes)

        return bytes
    }

    private fun createRandomFramedData(dataSize: Int): ByteArray {
        return createFramedData(createRandomData(dataSize))
    }

    private fun createFramedData(data: ByteArray): ByteArray {
        val dataSize = data.size
        val sizeHeader = encodeVarInt(dataSize)
        val bytes = ByteArray(dataSize + sizeHeader.size)

        for (i in 0 until dataSize) {
            val idx = sizeHeader.size + i
            bytes[idx] = data[i]
        }

        // Write header
        for (i in sizeHeader.indices) {
            bytes[i] = sizeHeader[i]
        }

        return bytes
    }

    private fun encodeVarInt(value: Int): ByteArray {
        try {
            val output = ByteArrayOutputStream()
            val codedOutputStream = CodedOutputStream.newInstance(output)
            codedOutputStream.writeUInt32NoTag(value)
            codedOutputStream.flush()
            return output.toByteArray()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun toByteBuf(bytes: ByteArray): ByteBuf {
        val byteBuf = wrappedBuffer(bytes)
        byteBufs.add(byteBuf)
        return byteBuf
    }

    private fun readInboundByteBuf(): ByteBuf? {
        val byteBuf = ch.readInbound<ByteBuf>()
        if (byteBuf != null) {
            byteBufs.add(byteBuf)
        }

        return byteBuf
    }
}
