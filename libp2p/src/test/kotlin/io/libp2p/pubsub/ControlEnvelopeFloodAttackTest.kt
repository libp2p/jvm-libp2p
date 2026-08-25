package io.libp2p.pubsub

import com.google.protobuf.ByteString
import io.libp2p.etc.util.netty.protobuf.LimitedProtobufVarint32FrameDecoder
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.protobuf.ProtobufDecoder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Drives raw frames through the real inbound pipeline and asserts that ones whose
 * control plane exceeds `maxControlMessageSize` are dropped before `ProtobufDecoder`
 * materialises anything.
 *
 * Envelope-heavy frames are the interesting case: per-shape messageID counters do not
 * bound them, because an empty envelope carries no messageIDs. The byte budget does,
 * shape-agnostically, since every envelope costs wire bytes.
 *
 * Limits are read back off a real [io.libp2p.pubsub.gossip.GossipRouter] rather
 * than hand-rolled, so these tests follow whatever `GossipRouter.rpcLimits`
 * actually plumbs and cannot drift from production wiring.
 */
class ControlEnvelopeFloodAttackTest {

    /** Teku's gossip configuration, mirroring `LibP2PParamsFactory.addGossipParamsMaxValues`. */
    private val tekuParams = GossipParams(
        maxGossipMessageSize = 12_234_442,
        maxPublishedMessages = 1000,
        maxTopicsPerPublishedMessage = 1,
        maxSubscriptions = 200,
        maxGraftMessages = 200,
        maxPruneMessages = 200,
        maxPeersSentInPruneMsg = 0,
        maxPeersAcceptedInPruneMsg = 0,
        maxIHaveLength = 5000,
        maxIWantMessageIds = 5000,
        maxIDontWantMessageIds = 5000,
    )

    private val tekuLimits = rpcLimitsOf(tekuParams)

    /** Teku mainnet-preset frame cap. */
    private val maxMsgSize = 12_234_442

    /**
     * 200_000 envelopes = 400_000 control bytes, comfortably over the 256 KiB budget.
     * The budget admits ~131_072 empty envelopes by design (spec D5), so a smaller
     * frame would legitimately be accepted.
     */
    private val entries = 200_000

    // ControlMessage field numbers, length-delimited wire type.
    private val ihaveTag = 0x0a.toByte()
    private val iwantTag = 0x12.toByte()

    @Test
    fun `negative control - empty publish entries are rejected before materialisation`() {
        val ch = pipeline(tekuLimits)

        // RPC field 2 (publish), length 0, repeated.
        val body = ByteArray(entries * 2) { if (it % 2 == 0) 0x12.toByte() else 0x00 }
        ch.writeInbound(framed(body))

        assertThat(ch.readInbound<Any?>()).isNull()
    }

    @Test
    fun `empty ControlIHave entries are rejected before materialisation`() {
        val ch = pipeline(tekuLimits)

        ch.writeInbound(controlFloodFrame(ihaveTag))

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received)
            .withFailMessage(
                "frame ACCEPTED: ProtobufDecoder materialised ${received?.control?.ihaveCount} " +
                    "ControlIHave objects from ${entries * 2} bytes of control payload"
            )
            .isNull()
    }

    @Test
    fun `empty ControlIWant entries are rejected before materialisation`() {
        val ch = pipeline(tekuLimits)

        ch.writeInbound(controlFloodFrame(iwantTag))

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received)
            .withFailMessage(
                "frame ACCEPTED: ProtobufDecoder materialised ${received?.control?.iwantCount} " +
                    "ControlIWant objects from ${entries * 2} bytes of control payload"
            )
            .isNull()
    }

    /**
     * Pins the wire-bytes-to-object ratio the budget is denominated against, measured
     * with the validator switched off so it stays valid however the validator changes:
     * one `ControlIHave` per two wire bytes of control payload.
     */
    @Test
    fun `each empty ControlIHave costs two wire bytes and one allocated object`() {
        val ch = pipeline(PubsubRpcLimits.NONE)

        ch.writeInbound(controlFloodFrame(ihaveTag))

        val received: Rpc.RPC? = ch.readInbound()
        assertThat(received!!.control.ihaveCount).isEqualTo(entries)
    }

    @Test
    fun `well-formed RPC under the same limits is still delivered`() {
        val ch = pipeline(tekuLimits)

        val ihave = Rpc.ControlIHave.newBuilder()
            .addMessageIDs(ByteString.copyFromUtf8("id"))
        val rpc = Rpc.RPC.newBuilder()
            .setControl(Rpc.ControlMessage.newBuilder().addIhave(ihave))
            .build()
        ch.writeInbound(framed(rpc.toByteArray()))

        assertThat(ch.readInbound<Rpc.RPC?>()).isEqualTo(rpc)
    }

    /**
     * Guards the tuning trap in any fix for #35.
     *
     * `GossipRpcPartsQueue` emits **one `ControlIHave` envelope per distinct
     * topic** (`IHavePart.appendToBuilder` merges by `topicID`), and `heartbeat()`
     * calls `emitGossip` per mesh and per fanout topic before a single
     * `flushAllPending()`. So one legitimate heartbeat RPC carries as many IHAVE
     * envelopes as there are topics gossiped that tick — dozens on a Teku node
     * subscribed to the attestation subnets.
     *
     * A per-frame envelope cap rejects the **whole frame**, including any publish
     * payloads batched alongside. Sizing that cap from
     * `GossipParams.maxIHaveMessages` (default 10) — which today is a
     * *per-heartbeat, ignore-the-excess* threshold at `GossipRouter.handleIHave`,
     * not a per-frame one — would make jvm-libp2p nodes drop each other's normal
     * heartbeat traffic.
     */
    @Test
    fun `legitimate multi-topic heartbeat RPC is accepted`() {
        val ch = pipeline(tekuLimits)

        val control = Rpc.ControlMessage.newBuilder()
        repeat(64) { topic ->
            control.addIhave(
                Rpc.ControlIHave.newBuilder()
                    .setTopicID("/eth2/deadbeef/beacon_attestation_$topic/ssz_snappy")
                    .addMessageIDs(ByteString.copyFromUtf8("msg-$topic"))
            )
        }
        val rpc = Rpc.RPC.newBuilder().setControl(control).build()

        ch.writeInbound(framed(rpc.toByteArray()))

        assertThat(ch.readInbound<Rpc.RPC?>())
            .withFailMessage(
                "a normal 64-topic heartbeat RPC was REJECTED by the pre-decode " +
                    "validator — the per-frame IHAVE envelope cap is sized too low"
            )
            .isEqualTo(rpc)
    }

    @Test
    fun `attack frame of empty subscriptions is rejected before materialisation`() {
        val ch = pipeline(tekuLimits)

        // RPC field 1 (subscriptions), length 0, repeated.
        val body = ByteArray(entries * 2) { if (it % 2 == 0) 0x0A.toByte() else 0x00 }
        ch.writeInbound(framed(body))

        assertThat(ch.readInbound<Any?>()).isNull()
    }

    @Test
    fun `attack frame of empty grafts is rejected before materialisation`() {
        val ch = pipeline(tekuLimits)
        ch.writeInbound(controlFloodFrame(0x1A.toByte())) // ControlMessage field 3
        assertThat(ch.readInbound<Any?>()).isNull()
    }

    @Test
    fun `attack frame of empty prunes is rejected before materialisation`() {
        val ch = pipeline(tekuLimits)
        ch.writeInbound(controlFloodFrame(0x22.toByte())) // ControlMessage field 4
        assertThat(ch.readInbound<Any?>()).isNull()
    }

    @Test
    fun `RPC carrying unknown fields under budget is still delivered`() {
        val ch = pipeline(tekuLimits)

        // A field number jvm-libp2p does not know, as a future libp2p version might add.
        val rpc = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder()
                        .setTopicID("/eth2/aabbccdd/beacon_block/ssz_snappy")
                        .addMessageIDs(ByteString.copyFromUtf8("id"))
                )
            )
            .build()
        val withUnknown = rpc.toByteArray() + byteArrayOf(0x70, 0x01) // field 14, varint

        ch.writeInbound(framed(withUnknown))

        assertThat(ch.readInbound<Any?>())
            .withFailMessage("unknown fields must be charged to the budget, never rejected outright")
            .isNotNull()
    }

    /**
     * `maxSubscriptions`/`maxGraftMessages`/`maxPruneMessages` are all left `null` here, so only
     * the control byte budget can reject this frame. Before the budget existed, an equivalent
     * frame was accepted by an unmodified per-shape validator and materialised one object per two
     * wire bytes.
     */
    @Test
    fun `control byte budget rejects floods that no per-shape count cap covers`() {
        val budgetOnly = PubsubRpcLimits.NONE.copy(maxControlMessageSize = 256 * 1024)
        val ch = pipeline(budgetOnly)

        ch.writeInbound(controlFloodFrame(0x1A.toByte())) // ControlMessage field 3, graft

        assertThat(ch.readInbound<Any?>()).isNull()
    }

    private fun pipeline(limits: PubsubRpcLimits) = EmbeddedChannel(
        LimitedProtobufVarint32FrameDecoder(maxMsgSize),
        RpcCountFrameDecoder(limits),
        ProtobufDecoder(Rpc.RPC.getDefaultInstance()),
    )

    /** Reads back the limits a real GossipRouter plumbs for [params]. */
    private fun rpcLimitsOf(params: GossipParams): PubsubRpcLimits {
        val builder = GossipRouterBuilder(params = params)
        try {
            val getter = AbstractRouter::class.java.getDeclaredMethod("getRpcLimits")
            getter.isAccessible = true
            return getter.invoke(builder.build()) as PubsubRpcLimits
        } finally {
            builder.scheduledAsyncExecutor.shutdownNow()
        }
    }

    /** RPC field 3 (control) holding [entries] empty sub-messages tagged [entryTag]. */
    private fun controlFloodFrame(entryTag: Byte): ByteBuf {
        val control = ByteArray(entries * 2) { if (it % 2 == 0) entryTag else 0x00 }

        val body = ByteBufAllocator.DEFAULT.buffer(control.size + 5)
        body.writeByte(0x1A) // field 3, length-delimited
        writeVarint32(body, control.size)
        body.writeBytes(control)

        val framed = ByteBufAllocator.DEFAULT.buffer(body.readableBytes() + 5)
        writeVarint32(framed, body.readableBytes())
        framed.writeBytes(body)
        body.release()
        return framed
    }

    private fun framed(body: ByteArray): ByteBuf {
        val framed = ByteBufAllocator.DEFAULT.buffer(body.size + 5)
        writeVarint32(framed, body.size)
        framed.writeBytes(body)
        return framed
    }

    private fun writeVarint32(buf: ByteBuf, value: Int) {
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
