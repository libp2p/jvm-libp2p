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
    fun `rejects when subscriptions count exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .apply { repeat(3) { addSubscriptions(subOpt("t$it")) } }
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxSubscriptions = 2)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when topicIDs per publish exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder().addPublish(message(topics = 5)).build()
        val limits = PubsubRpcLimits.NONE.copy(maxTopicsPerPublishedMessage = 4)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when ihave messageIDs total exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .addIhave(ihave(ids = 4))
                    .addIhave(ihave(ids = 4))
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxIHaveMessageIds = 7)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when iwant messageIDs total exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(Rpc.ControlMessage.newBuilder().addIwant(iwant(ids = 10)))
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxIWantMessageIds = 9)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when graft count exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("a"))
                    .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("b"))
                    .addGraft(Rpc.ControlGraft.newBuilder().setTopicID("c"))
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxGraftMessages = 2)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when prune count exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .addPrune(pruneWithPeers(peers = 0))
                    .addPrune(pruneWithPeers(peers = 0))
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxPruneMessages = 1)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when peers per prune exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(Rpc.ControlMessage.newBuilder().addPrune(pruneWithPeers(peers = 17)))
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxPeersPerPruneMessage = 16)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
    }

    @Test
    fun `rejects when idontwant messageIDs exceed limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(Rpc.ControlMessage.newBuilder().addIdontwant(idontwant(ids = 5)))
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxIDontWantMessageIds = 4)
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
    fun `rejects when idontwant message count exceeds limit`() {
        val rpc = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .addIdontwant(idontwant(ids = 1))
                    .addIdontwant(idontwant(ids = 1))
                    .addIdontwant(idontwant(ids = 1))
            )
            .build()
        val limits = PubsubRpcLimits.NONE.copy(maxIDontWantMessages = 2)
        assertThat(RpcMessageCountValidator.validate(bytesOf(rpc), limits))
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)
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
}
