package io.libp2p.pubsub

import com.google.protobuf.ByteString
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcCountFrameDecoderTest {

    private val limits = PubsubRpcLimits.NONE.copy(
        maxPublishedMessages = 2,
        rejectEmptyPublishEntries = true,
    )

    private fun pipeline() = EmbeddedChannel(
        RpcCountFrameDecoder(limits),
        ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
    )

    @Test
    fun `forwards an accepted RPC unchanged`() {
        val ch = pipeline()
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x")))
            .build()

        ch.writeInbound(Unpooled.wrappedBuffer(rpc.toByteArray()))

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received).isEqualTo(rpc)
    }

    @Test
    fun `drops an RPC containing an empty publish entry`() {
        val ch = pipeline()
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.getDefaultInstance())
            .build()

        ch.writeInbound(Unpooled.wrappedBuffer(rpc.toByteArray()))

        val received: Any? = ch.readInbound()
        assertThat(received).isNull()
    }

    @Test
    fun `drops an RPC whose publish count exceeds limits`() {
        val ch = pipeline()
        val rpc = Rpc.RPC.newBuilder()
            .apply {
                repeat(3) {
                    addPublish(Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x$it")))
                }
            }
            .build()

        ch.writeInbound(Unpooled.wrappedBuffer(rpc.toByteArray()))

        val received: Any? = ch.readInbound()
        assertThat(received).isNull()
    }
}
