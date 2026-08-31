package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.MessageId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Asserts the *symmetry* invariant: every RPC our own outbound batcher emits fits inside the
 * inbound [GossipParams.maxControlMessageSize] budget, so two peers running this code cannot
 * reject each other's traffic.
 *
 * go-libp2p-pubsub (PR #707) and rust-libp2p (PR #6468) both bound inbound allocation with a
 * pre-decode cumulative control-byte budget rather than per-shape envelope counters. Adopting the
 * same rule requires the batcher to respect the same budget, otherwise we repeat the
 * `maxIHaveMessages = 10` mistake in a new denomination.
 *
 * Control bytes are computed the way go does it in `pb/validator.go`: everything except `publish`
 * payloads and `RPC.partial` counts, and for `publish` only the envelope overhead is charged.
 *
 * The categories are drained both separately and *together*. Together is load-bearing:
 * `DefaultGossipRpcPartsQueue` puts publish, IHAVE and IWANT parts on one shared BULK priority
 * list and merges them into a single RPC, so per-category drains alone cannot observe the sum.
 */
class ControlBytesBudgetSymmetryTest {

    /**
     * Teku's gossip configuration, mirroring every value
     * `LibP2PParamsFactory.addGossipParamsMaxValues` sets. Note `maxIDontWantMessageIds`:
     * Teku overrides it to 5000, well below the library default of
     * `maxIHaveLength * maxIHaveMessages` = 50 000, so Teku's IDONTWANT frames are far
     * smaller than a default-configured node's. The library defaults are covered separately
     * below, because they are the harder case.
     */
    private val tekuParams = GossipParams(
        maxGossipMessageSize = 12_234_442,
        maxPublishedMessages = 1000,
        maxTopicsPerPublishedMessage = 1,
        maxPeersSentInPruneMsg = 0,
        maxPeersAcceptedInPruneMsg = 0,
        maxIHaveLength = 5000,
        maxIDontWantMessageIds = 5000,
    )

    /**
     * Library defaults. `maxIWantMessageIds` / `maxPublishedMessages` are `null` here, which
     * `takeBatch` reads as unbounded, so only the byte budget bounds those categories.
     */
    private val defaultParams = GossipParams()

    /** Ethereum gossipsub message IDs are 20 bytes. */
    private fun messageId(i: Int): MessageId = messageId(i, 20)

    /** The library default `messageId` is `from || seqno`, 46 bytes; see `AbstractRouter`. */
    private fun messageId(i: Int, size: Int): MessageId =
        ByteArray(size) { (i + it).toByte() }.toWBytes()

    private fun topic(i: Int) = "/eth2/aabbccdd/beacon_attestation_$i/ssz_snappy"

    private fun publishMessage() = Rpc.Message.newBuilder()
        .addTopicIDs("/eth2/aabbccdd/beacon_block/ssz_snappy")
        .setData(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
        .build()

    private class Queue(params: GossipParams) : DefaultGossipRpcPartsQueue(params)

    private fun varintSize(v: Int): Int {
        var n = 1
        var x = v ushr 7
        while (x != 0) {
            n++
            x = x ushr 7
        }
        return n
    }

    /** go-libp2p-pubsub `ValidateRawRPCControlMessageSize` semantics. */
    private fun controlBytes(rpc: Rpc.RPC): Int {
        var n = 0
        rpc.subscriptionsList.forEach {
            val s = it.serializedSize
            n += 1 + varintSize(s) + s
        }
        if (rpc.hasControl()) {
            val s = rpc.control.serializedSize
            n += 1 + varintSize(s) + s
        }
        rpc.publishList.forEach {
            val s = it.serializedSize
            n += 1 + varintSize(s) + s - it.data.size() // payload not charged
        }
        // RPC.partial (tag 10) is NOT fully exempt: the production validator (see
        // RpcMessageCountValidator.scanPartial) only exempts the payload lengths of
        // partialMessage (3) and partsMetadata (4) inside it, still charging the envelope
        // and any unknown fields. No current part type emits RPC.partial, so this
        // simplification is inert today, but would under-count the budget if one ever did.
        return n
    }

    private fun drain(params: GossipParams, label: String, fill: (Queue) -> Unit): Int {
        val q = Queue(params)
        fill(q)
        var batches = 0
        var worst = 0
        while (true) {
            val b = q.takeBatch() ?: break
            batches++
            worst = maxOf(worst, controlBytes(b.rpc))
            if (batches > 1000) break // safety
        }
        println("%-44s batches=%3d  worstControlBytes=%9d (%7.1f KiB)".format(label, batches, worst, worst / 1024.0))
        return worst
    }

    private fun assertFitsBudget(params: GossipParams, worst: Int) {
        assertThat(worst)
            .withFailMessage(
                "our own batcher emits %d control bytes in one RPC, over the %d byte budget - " +
                    "peers running this code would reject each other's traffic",
                worst,
                params.maxControlMessageSize
            )
            .isLessThanOrEqualTo(params.maxControlMessageSize)
    }

    @Test
    fun `every batch our own batcher emits fits inside the control byte budget`() {
        val worst = listOf(
            drain(tekuParams, "IDONTWANT x maxIDontWantMessageIds") { q ->
                repeat(tekuParams.maxIDontWantMessageIds) { q.addIDontWant(messageId(it)) }
            },
            drain(tekuParams, "IHAVE x maxIHaveLength (64 topics)") { q ->
                repeat(tekuParams.maxIHaveLength) { q.addIHave(messageId(it), topic(it % 64)) }
            },
            drain(tekuParams, "IWANT x 5000 (Teku-configured value)") { q ->
                repeat(5000) { q.addIWant(messageId(it)) }
            },
            drain(tekuParams, "subscriptions x 200 (Teku-configured value)") { q ->
                repeat(200) { q.addSubscribe(topic(it)) }
            },
            drain(tekuParams, "graft x 200 (Teku-configured value)") { q ->
                repeat(200) { q.addGraft(topic(it)) }
            },
            drain(tekuParams, "prune x 200 (Teku-configured value)") { q ->
                repeat(200) { q.addPrune(topic(it), 60L, listOf(PeerId.random())) }
            },
            drain(tekuParams, "publish x maxPublishedMessages (small messages)") { q ->
                repeat(tekuParams.maxPublishedMessages!!) { q.addPublish(publishMessage()) }
            },
            drain(tekuParams, "control extensions (single envelope)") { q ->
                q.addControlExtensions(Rpc.ControlExtensions.newBuilder().setPartialMessages(true).build())
            },
        ).maxOrNull()!!

        assertFitsBudget(tekuParams, worst)
    }

    /**
     * Fills one queue with IHAVE, IWANT and publish parts *interleaved*, as a heartbeat tick
     * does. Order matters: appending each category as a contiguous run lets the per-category
     * counters split the batch by accident, hiding the fact that nothing bounds their sum.
     */
    private fun Queue.fillInterleaved(iHaves: Int, iWants: Int, publishes: Int, idSize: Int) {
        val steps = maxOf(iHaves, iWants, publishes)
        repeat(steps) { i ->
            if (i * iHaves / steps < (i + 1) * iHaves / steps) addIHave(messageId(i, idSize), topic(i % 64))
            if (i * iWants / steps < (i + 1) * iWants / steps) addIWant(messageId(i, idSize))
            if (i * publishes / steps < (i + 1) * publishes / steps) addPublish(publishMessage())
        }
    }

    @Test
    fun `IHAVE, IWANT and publish share one priority list and are bounded together`() {
        val worst = drain(tekuParams, "IHAVE + IWANT + publish interleaved") { q ->
            q.fillInterleaved(
                iHaves = tekuParams.maxIHaveLength,
                iWants = 5000,
                publishes = tekuParams.maxPublishedMessages!!,
                idSize = 20
            )
        }

        assertFitsBudget(tekuParams, worst)
    }

    @Test
    fun `library defaults with 46 byte message ids stay inside the budget`() {
        val ids = 5000
        val worst = listOf(
            drain(defaultParams, "default: IHAVE x maxIHaveLength (46B ids)") { q ->
                repeat(defaultParams.maxIHaveLength) { q.addIHave(messageId(it, 46), topic(it % 64)) }
            },
            drain(defaultParams, "default: IHAVE + IWANT + publish interleaved") { q ->
                q.fillInterleaved(
                    iHaves = defaultParams.maxIHaveLength,
                    iWants = ids,
                    publishes = 1000,
                    idSize = 46
                )
            },
            drain(defaultParams, "default: IDONTWANT x maxIDontWantMessageIds") { q ->
                repeat(defaultParams.maxIDontWantMessageIds) { q.addIDontWant(messageId(it, 46)) }
            },
        ).maxOrNull()!!

        assertFitsBudget(defaultParams, worst)
    }
}
