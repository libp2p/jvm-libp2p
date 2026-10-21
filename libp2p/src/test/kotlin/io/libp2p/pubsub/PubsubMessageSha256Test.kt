package io.libp2p.pubsub

import com.google.protobuf.ByteString
import io.libp2p.core.crypto.sha256
import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.toProtobuf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.Random

class PubsubMessageSha256Test {

    private class Msg(override val protobufMessage: Rpc.Message) : AbstractPubsubMessage() {
        override val messageId: MessageId = WBytes(protobufMessage.data.toByteArray())
    }

    private fun message(
        topic: String = "topic",
        data: ByteArray = "hello".toByteArray(),
        seqno: ByteArray? = null,
        from: ByteArray? = null,
        signature: ByteArray? = null
    ): Rpc.Message {
        val builder = Rpc.Message.newBuilder().addTopicIDs(topic).setData(data.toProtobuf())
        seqno?.also { builder.setSeqno(it.toProtobuf()) }
        from?.also { builder.setFrom(it.toProtobuf()) }
        signature?.also { builder.setSignature(it.toProtobuf()) }
        return builder.build()
    }

    @Test
    fun `messageSha256 is the sha256 of the serialized protobuf message`() {
        val random = Random(1)
        val payloads = listOf(ByteArray(0), ByteArray(1), ByteArray(1000), ByteArray(5000), ByteArray(130_000))
            .onEach { random.nextBytes(it) }
        for (payload in payloads) {
            val fullMsg = message(
                topic = "/eth2/00000000/beacon_block/ssz_snappy",
                data = payload,
                seqno = ByteArray(8).also { random.nextBytes(it) },
                from = ByteArray(38).also { random.nextBytes(it) },
                signature = ByteArray(64).also { random.nextBytes(it) }
            )
            val minimalMsg = message(data = payload)
            for (protoMsg in listOf(fullMsg, minimalMsg)) {
                assertThat(Msg(protoMsg).messageSha256())
                    .`as`("payload of ${payload.size} bytes")
                    .isEqualTo(sha256(protoMsg.toByteArray()))
            }
        }
    }

    @Test
    fun `messageSha256 covers every field of the message`() {
        val base = message(seqno = byteArrayOf(1), from = byteArrayOf(2), signature = byteArrayOf(3))
        val variants = listOf(
            base.toBuilder().clearTopicIDs().addTopicIDs("other").build(),
            base.toBuilder().setData(ByteString.copyFromUtf8("hellO")).build(),
            base.toBuilder().setSeqno(byteArrayOf(9).toProtobuf()).build(),
            base.toBuilder().setFrom(byteArrayOf(9).toProtobuf()).build(),
            base.toBuilder().setSignature(byteArrayOf(9).toProtobuf()).build()
        )
        val baseSha = Msg(base).messageSha256()
        variants.forEach { assertThat(Msg(it).messageSha256()).isNotEqualTo(baseSha) }
        assertThat(Msg(message(seqno = byteArrayOf(1), from = byteArrayOf(2), signature = byteArrayOf(3))).messageSha256())
            .isEqualTo(baseSha)
    }

    @Test
    fun `messageSha256 is computed once per message instance`() {
        val msg = Msg(message())
        val first = msg.messageSha256()
        assertThat(msg.messageSha256()).isSameAs(first)
        assertThat(msg.hashCode()).isEqualTo(first.contentHashCode())
    }

    @Test
    fun `default interface implementation and AbstractPubsubMessage agree`() {
        val protoMsg = message(data = ByteArray(3000).also { Random(3).nextBytes(it) })
        val viaInterface = object : PubsubMessage {
            override val protobufMessage = protoMsg
            override val messageId = WBytes(byteArrayOf())
            override fun equals(other: Any?) = this === other
            override fun hashCode() = 0
        }
        assertThat(Msg(protoMsg).messageSha256()).isEqualTo(viaInterface.messageSha256())
    }
}
