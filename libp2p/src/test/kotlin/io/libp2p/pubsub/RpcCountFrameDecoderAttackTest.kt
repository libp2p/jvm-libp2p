package io.libp2p.pubsub

import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcCountFrameDecoderAttackTest {

    private val limits = PubsubRpcLimits.NONE.copy(
        maxPublishedMessages = 1000,
        rejectEmptyPublishEntries = true,
    )

    @Test
    fun `attack frame of empty publish entries is rejected before materialisation`() {
        val maxMsgSize = 12_234_442 // Teku mainnet-preset

        val ch = EmbeddedChannel(
            LimitedProtobufVarint32FrameDecoder(maxMsgSize),
            RpcCountFrameDecoder(limits),
            ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
        )

        // 100_000 empty publish entries: large enough to demonstrate amplification
        // would be catastrophic, small enough to keep the test cheap.
        val entries = 100_000
        val body = ByteArray(entries * 2) { if (it % 2 == 0) 0x12.toByte() else 0x00.toByte() }

        // Write a length-prefixed frame manually: varint(length) || body.
        val framed = ByteBufAllocator.DEFAULT.buffer(body.size + 5)
        writeVarint32(framed, body.size)
        framed.writeBytes(body)

        ch.writeInbound(framed)

        val received: Any? = ch.readInbound()
        assertThat(received).isNull() // ProtobufDecoder never produced an Rpc.RPC
    }

    @Test
    fun `well-formed RPC under the same limits is still delivered`() {
        val maxMsgSize = 12_234_442

        val ch = EmbeddedChannel(
            LimitedProtobufVarint32FrameDecoder(maxMsgSize),
            RpcCountFrameDecoder(limits),
            ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
        )

        val rpc = Rpc.RPC.newBuilder()
            .addPublish(
                Rpc.Message.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8("ok"))
            )
            .build()
        val body = rpc.toByteArray()

        val framed = ByteBufAllocator.DEFAULT.buffer(body.size + 5)
        writeVarint32(framed, body.size)
        framed.writeBytes(body)

        ch.writeInbound(framed)

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received).isEqualTo(rpc)
    }

    private fun writeVarint32(buf: io.netty.buffer.ByteBuf, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                buf.writeByte(v)
                return
            }
            buf.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }
}
