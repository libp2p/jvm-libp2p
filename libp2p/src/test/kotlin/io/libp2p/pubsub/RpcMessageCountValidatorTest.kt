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

    /**
     * The gap [PubsubRpcLimits.maxTotalFields] closes: 40_000 empty `ControlIHave` envelopes are
     * 80 KB on the wire, comfortably inside a 256 KiB control budget, yet protobuf-java would
     * materialise 40_000 objects from them. The byte budget alone accepts this.
     */
    @Test
    fun `empty control envelopes under the byte budget are rejected on field count`() {
        val attack = controlOf(emptyIhaveEntries = 40_000)
        val byteBudgetOnly = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), byteBudgetOnly))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)

        val withFieldBudget = byteBudgetOnly.copy(maxTotalFields = 32768)
        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), withFieldBudget))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("total fields > 32768"))
    }

    @Test
    fun `empty subscription envelopes under the byte budget are rejected on field count`() {
        // 40_000 empty SubOpts entries: RPC field 1, length-delimited, zero length.
        val attack = ByteArray(40_000 * 2) { if (it % 2 == 0) 0x0A else 0x00 }
        val limits = PubsubRpcLimits.NONE.copy(
            maxControlMessageSize = 256 * 1024,
            maxTotalFields = 32768,
        )

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("total fields > 32768"))
    }

    @Test
    fun `field budget counts fields nested inside control entries`() {
        // One ihave carrying 100 messageIDs: 1 control + 1 ihave + 1 topicID + 100 ids = 103.
        val rpc = Rpc.RPC.newBuilder()
            .setControl(Rpc.ControlMessage.newBuilder().addIhave(ihave(ids = 100)))
            .build()

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limitsWithFields(103)))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limitsWithFields(102)))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("total fields > 102"))
    }

    @Test
    fun `field budget counts peers nested inside a prune entry`() {
        // control + prune + topicID + 3 * (peers envelope + peerID) = 9.
        val rpc = Rpc.RPC.newBuilder()
            .setControl(Rpc.ControlMessage.newBuilder().addPrune(pruneWithPeers(peers = 3)))
            .build()

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limitsWithFields(9)))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limitsWithFields(8)))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("total fields > 8"))
    }

    @Test
    fun `no field budget configured means no field count enforcement`() {
        val attack = controlOf(emptyIhaveEntries = 40_000)
        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), PubsubRpcLimits.NONE))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    @Test
    fun `well-formed RPC is accepted under a realistic field budget`() {
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

        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limitsWithFields(32768)))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    /**
     * Groups are the one unknown shape protobuf-java expands field by field, so the field budget
     * has to walk into them: 40_000 tiny fields inside an unknown group are 80 KB on the wire and
     * would materialise 40_000 entries in a nested UnknownFieldSet.
     */
    @Test
    fun `fields inside an unknown group are counted`() {
        val attack = unknownGroup(fieldNumber = 99, innerFields = 40_000)
        val limits = PubsubRpcLimits.NONE.copy(
            maxControlMessageSize = 256 * 1024,
            maxTotalFields = 32768,
        )

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(attack), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Rejected("total fields > 32768"))
    }

    @Test
    fun `nested unknown groups do not recurse the walker`() {
        // 5000 levels of nesting: a stack-recursive walk would overflow, the depth counter does not.
        val depth = 5_000
        val open = (1..depth).flatMap { varint((99 shl 3) or 3).toList() }.toByteArray()
        val close = (1..depth).flatMap { varint((99 shl 3) or 4).toList() }.toByteArray()
        val limits = PubsubRpcLimits.NONE.copy(maxTotalFields = 32768)

        assertThat(RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(open + close), limits))
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    /**
     * Pins the assumption that lets the walker leave unknown length-delimited fields alone:
     * protobuf-java keeps the whole body as one opaque ByteString and never parses its interior,
     * so however many fields it appears to hold, it costs one allocation - unlike a group.
     */
    @Test
    fun `unknown length-delimited bodies are one ByteString, so their interior needs no counting`() {
        val inner = ByteArray(1_000 * 2) { if (it % 2 == 0) 0x08 else 0x01 }
        val raw = varint((99 shl 3) or 2) + varint(inner.size) + inner

        val parsed = Rpc.RPC.parseFrom(raw).unknownFields.asMap()[99]!!
        assertThat(parsed.lengthDelimitedList).hasSize(1)
        assertThat(parsed.lengthDelimitedList.single().size()).isEqualTo(inner.size)
        assertThat(parsed.varintList).isEmpty()
        assertThat(parsed.groupList).isEmpty()

        // A group carrying the same 1000 fields expands into 1000 retained entries instead.
        val asGroup = Rpc.RPC.parseFrom(unknownGroup(fieldNumber = 99, innerFields = 1_000))
        assertThat(asGroup.unknownFields.asMap()[99]!!.groupList.single().asMap()[1]!!.varintList)
            .hasSize(1_000)
    }

    private fun limitsWithFields(max: Int) = PubsubRpcLimits.NONE.copy(maxTotalFields = max)

    /** An unknown group holding [innerFields] two-byte varint fields. */
    private fun unknownGroup(fieldNumber: Int, innerFields: Int): ByteArray {
        val inner = ByteArray(innerFields * 2) { if (it % 2 == 0) 0x08 else 0x01 }
        return varint((fieldNumber shl 3) or 3) + inner + varint((fieldNumber shl 3) or 4)
    }

    /** A `control` field (RPC field 3) holding [emptyIhaveEntries] zero-length ihave envelopes. */
    private fun controlOf(emptyIhaveEntries: Int): ByteArray {
        val body = ByteArray(emptyIhaveEntries * 2) { if (it % 2 == 0) 0x0A else 0x00 }
        return byteArrayOf(0x1A) + varint(body.size) + body
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
