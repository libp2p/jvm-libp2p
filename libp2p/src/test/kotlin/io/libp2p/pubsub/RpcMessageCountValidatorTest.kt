package io.libp2p.pubsub

import com.google.protobuf.ByteString
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class RpcMessageCountValidatorTest {

    private val unlimited = PubsubRpcLimits.NONE.copy(rejectEmptyPublishEntries = true)

    private fun bytesOf(rpc: Rpc.RPC) = Unpooled.wrappedBuffer(rpc.toByteArray())

    private fun message(topics: Int = 0): Rpc.Message {
        val b = Rpc.Message.newBuilder().setData(ByteString.copyFromUtf8("x"))
        repeat(topics) { b.addTopicIDs("t$it") }
        return b.build()
    }

    private fun subOpt(topic: String) =
        Rpc.RPC.SubOpts.newBuilder().setTopicid(topic).setSubscribe(true).build()

    private fun ihave(ids: Int) = Rpc.ControlIHave.newBuilder()
        .setTopicID("t")
        .also { repeat(ids) { i -> it.addMessageIDs(ByteString.copyFromUtf8("m$i")) } }
        .build()

    private fun iwant(ids: Int) = Rpc.ControlIWant.newBuilder()
        .also { repeat(ids) { i -> it.addMessageIDs(ByteString.copyFromUtf8("m$i")) } }
        .build()

    private fun idontwant(ids: Int) = Rpc.ControlIDontWant.newBuilder()
        .also { repeat(ids) { i -> it.addMessageIDs(ByteString.copyFromUtf8("m$i")) } }
        .build()

    private fun pruneWithPeers(peers: Int) = Rpc.ControlPrune.newBuilder()
        .setTopicID("t")
        .also {
            repeat(peers) { i ->
                it.addPeers(Rpc.PeerInfo.newBuilder().setPeerID(ByteString.copyFromUtf8("p$i")))
            }
        }
        .build()

    @Test
    fun `rejects RPC containing an empty publish entry`() {
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(Rpc.Message.getDefaultInstance())
            .build()

        val result = RpcMessageCountValidator.validate(bytesOf(rpc), unlimited)

        assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `accepts non-empty publish when allowed`() {
        val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 1)).build()
        val limits = PubsubRpcLimits.NONE
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `rejects when publish count exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .apply { repeat(3) { addPublish(message(topics = 1)) } }
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxPublishedMessages = 2)
        val result = RpcMessageCountValidator.validate(bytesOf(rpc), limits)
        assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when topicIDs per publish exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 5)).build()
        val limits = PubsubRpcLimits.NONE.copy(maxTopicsPerPublishedMessage = 4)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `accepts well-formed RPC under every configured limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .addSubscriptions(subOpt("t"))
            .addPublish(message(topics = 1))
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .addIhave(ihave(ids = 2))
                    .addIwant(iwant(ids = 2))
                    .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("t"))
                    .addPrune(pruneWithPeers(peers = 1))
                    .addIdontwant(idontwant(ids = 1))
            )
            .build()
        val limits = PubsubRpcLimits(
            maxPublishedMessages = 10,
            maxTopicsPerPublishedMessage = 4,
            maxSubscriptions = 10,
            maxIHaveMessageIds = 10,
            maxIWantMessageIds = 10,
            maxGraftMessages = 10,
            maxPruneMessages = 10,
            maxPeersPerPruneMessage = 10,
            maxIDontWantMessages = 10,
            maxIDontWantMessageIds = 10,
            rejectEmptyPublishEntries = true,
        )
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `rejects truncated input as malformed`() {
        val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 1)).build()
        val full = rpc.toByteArray()
        val truncated = full.copyOfRange(0, full.size - 1)
        val result = RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(truncated), unlimited)
        assertThat(result).isInstanceOf(RpcMessageCountValidator.Result.Malformed::class.java)
    }

    @Test
    fun `attack payload of empty publish entries rejected on first entry`() {
        // 1000 empty publish entries, which would expand to 1000 Rpc.Message objects.
        val attack = ByteArray(2 * 1000) { if (it % 2 == 0) 0x12.toByte() else 0x00.toByte() }
        val result = RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), unlimited)
        assertThat(result).isEqualTo(RpcMessageCountValidator.Result.Rejected("empty publish entry"))
    }

    @Test
    fun `accepts when count equals limit exactly`() {
        val rpc = Rpc.RPC.newBuilder()
            .apply { repeat(3) { addPublish(message(topics = 1)) } }
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxPublishedMessages = 3)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `rejects when control bytes exceed the budget`() {
        // 200_000 empty ControlIHave envelopes = 400_000 control bytes.
        val control = ByteArray(200_000 * 2) { if (it % 2 == 0) 0x0A else 0x00 }
        val raw = byteArrayOf(0x1A) + varint(control.size) + control
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(raw), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("control bytes > ${256 * 1024}"))
    }

    @Test
    fun `accepts control bytes under the budget`() {
        val control = ByteArray(1_000 * 2) { if (it % 2 == 0) 0x0A else 0x00 }
        val raw = byteArrayOf(0x1A) + varint(control.size) + control
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(raw), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `publish payloads are not charged to the control budget`() {
        // One publish Message with a 1 MiB data payload, against a 4 KiB budget.
        val rpc = Rpc.RPC.newBuilder()
            .addPublish(
                Rpc.Message.newBuilder()
                    .setData(ByteString.copyFrom(ByteArray(1024 * 1024)))
                    .addTopicIDs("t")
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 4096)

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `publish envelope overhead is charged to the control budget`() {
        // 5000 publish entries, each with a tiny payload: payloads are exempt but the
        // envelope framing and topicIDs are not, so the budget still trips.
        val rpc = Rpc.RPC.newBuilder()
            .also { r ->
                repeat(5_000) {
                    r.addPublish(
                        Rpc.Message.newBuilder()
                            .setData(ByteString.copyFromUtf8("x"))
                            .addTopicIDs("/eth2/aabbccdd/beacon_block/ssz_snappy")
                    )
                }
            }
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 4096)

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("control bytes > 4096"))
    }

    @Test
    fun `unknown fields are charged to the budget, not rejected`() {
        val small = unknownVarintField(14, 1) + unknownVarintField(15, 1)
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(small), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)

        // Enough unknown fields to blow the budget are rejected on size, not on being unknown.
        val many = ByteArray(200_000 * 2) { if (it % 2 == 0) 0x70 else 0x01 }
        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(many), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("control bytes > ${256 * 1024}"))
    }

    @Test
    fun `no budget configured means no control byte enforcement`() {
        val control = ByteArray(200_000 * 2) { if (it % 2 == 0) 0x0A else 0x00 }
        val raw = byteArrayOf(0x1A) + varint(control.size) + control

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(raw), PubsubRpcLimits.NONE))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `partial extension unknown fields are charged to the budget`() {
        // 200_000 unknown varint fields inside `partial`, mirroring the top-level unknown-field
        // case: the exemption only covers opaque payload bytes, not the whole field.
        val body = ByteArray(200_000 * 2) { if (it % 2 == 0) 0x70 else 0x01 }
        val raw = byteArrayOf(0x52) + varint(body.size) + body
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(raw), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("control bytes > ${256 * 1024}"))
    }

    @Test
    fun `partial payloads are not charged to the control budget`() {
        // A partial extension with a 1 MiB partialMessage payload, against a 4 KiB budget.
        val rpc = Rpc.RPC.newBuilder()
            .setPartial(
                Rpc.PartialMessagesExtension.newBuilder()
                    .setPartialMessage(ByteString.copyFrom(ByteArray(1024 * 1024)))
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 4096)

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `partsMetadata payloads are not charged to the control budget`() {
        // partsMetadata (field 4) shares the exemption branch with partialMessage (field 3):
        // a 1 MiB payload against a 4 KiB budget must still be accepted.
        val rpc = Rpc.RPC.newBuilder()
            .setPartial(
                Rpc.PartialMessagesExtension.newBuilder()
                    .setPartsMetadata(ByteString.copyFrom(ByteArray(1024 * 1024)))
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 4096)

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `partial field at wrong wire type is charged, not exempted`() {
        val limits = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)

        val one = byteArrayOf(0x50, 0x01)
        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(one), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)

        // Enough varint-typed `partial` fields to blow the budget confirms they're charged like
        // any other field, not silently exempted because the field number matches RPC_PARTIAL.
        val many = ByteArray(200_000 * 2) { if (it % 2 == 0) 0x50 else 0x01 }
        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(many), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("control bytes > ${256 * 1024}"))
    }

    private fun unknownVarintField(fieldNumber: Int, value: Int): ByteArray =
        byteArrayOf((fieldNumber shl 3).toByte(), value.toByte())

    private fun varint(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.add(v.toByte())
                break
            }
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        return out.toByteArray()
    }
}
