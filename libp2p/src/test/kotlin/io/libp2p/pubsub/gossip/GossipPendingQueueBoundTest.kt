package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.NOP_ROUTER_VALIDATOR
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.SimpleSeenCache
import io.libp2p.pubsub.TTLSeenCache
import io.libp2p.pubsub.TestRouter
import io.libp2p.pubsub.TopicSubscriptionFilter
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

/**
 * Regression test for unbounded growth of the per-peer pending outbound RPC parts queue.
 *
 * Since size-aware RPC part batching was introduced, [io.libp2p.pubsub.RpcPartsQueue.takeBatch]
 * intentionally leaves parts queued when they do not fit into a single protocol-limit-valid RPC.
 * That retention is unconditional, so the only thing that reclaims the queue of a peer which
 * never drains is slow-peer handling, driven by [GossipParams.slowPeerPendingBytesThreshold].
 *
 * If that threshold defaults to a value which is never reachable in practice, a peer that stops
 * accepting outbound data accumulates pending parts for as long as the connection stays open,
 * which retains every queued message payload on the heap.
 *
 * These tests use default [GossipParams] on purpose: the existing slow-peer tests all configure
 * `slowPeerPendingBytesThreshold` explicitly, so they only ever exercise the reclaim path in its
 * enabled state.
 */
class GossipPendingQueueBoundTest : GossipTestsBase() {

    private val payloadSize = 20 * 1024
    private val messageCount = 1000

    /** Total offered to the stalled peer: ~20 MiB. */
    private val offeredBytes = payloadSize.toLong() * messageCount

    /**
     * Generous ceiling: well above any legitimate steady-state backlog, but far below the
     * ~20 MiB we offer, so an unbounded queue fails unambiguously.
     */
    private val maxToleratedPendingBytes = 8 * 1024 * 1024

    @Test
    fun `pending outbound queue stays bounded for a peer that never drains`() {
        val fuzz = DeterministicFuzz()
        // Default params on purpose - only floodPublish is forced so every publish targets the peer.
        val params = GossipParams(floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH)

        val gossipRouter = fuzz.createInspectableRouter(params)
        val mockRouter = fuzz.createMockRouter()
        val connection = gossipRouter.connectSemiDuplex(mockRouter, pubsubLogs = LogLevel.ERROR)
        val router = gossipRouter.router as InspectableGossipRouter
        val outboundChannel = connection.conn1.ch1

        mockRouter.router.subscribe("topic1")
        fuzz.timeController.addTime(100.millis)

        // The peer stops accepting outbound data and never recovers.
        outboundChannel.setWritableForTest(false)

        val payload = ByteArray(payloadSize) { 0x5A }
        repeat(messageCount) { i ->
            router.publish(newMessage("topic1", i.toLong(), payload))
            // Advance past heartbeats so slow-peer detection has many chances to run.
            fuzz.timeController.addTime(200.millis)
        }
        fuzz.timeController.addTime(30.seconds)

        val pendingBytes = router.pendingOutboundBytes()
        assertThat(pendingBytes)
            .withFailMessage(
                "Pending outbound queue grew to %d bytes after offering %d bytes to a peer that " +
                    "never drains; nothing reclaimed it, so every queued payload stays on the heap",
                pendingBytes,
                offeredBytes
            )
            .isLessThan(maxToleratedPendingBytes)
    }

    @Test
    fun `default slow peer pending bytes threshold is reachable in practice`() {
        // A threshold that cannot be reached means the pending queue has no effective bound.
        assertThat(GossipParams().slowPeerPendingBytesThreshold)
            .withFailMessage(
                "slowPeerPendingBytesThreshold defaults to %d, which no real queue reaches, so " +
                    "retained RPC parts are never reclaimed",
                GossipParams().slowPeerPendingBytesThreshold
            )
            .isLessThan(Int.MAX_VALUE)
    }

    private fun DeterministicFuzz.createInspectableRouter(params: GossipParams): TestRouter {
        return createTestRouter { executor, currentTimeSupplier, random ->
            val score = DefaultGossipScore(GossipScoreParams(), executor, currentTimeSupplier)
            InspectableGossipRouter(
                params = params,
                scoreParams = GossipScoreParams(),
                currentTimeSupplier = currentTimeSupplier,
                random = random,
                executor = executor,
                score = score
            )
        }
    }

    private class InspectableGossipRouter(
        params: GossipParams,
        scoreParams: GossipScoreParams,
        currentTimeSupplier: CurrentTimeSupplier,
        random: Random,
        executor: ScheduledExecutorService,
        score: DefaultGossipScore
    ) : GossipRouter(
        params = params,
        scoreParams = scoreParams,
        currentTimeSupplier = currentTimeSupplier,
        random = random,
        name = "InspectableGossipRouter",
        mCache = MCache(params.gossipSize, params.gossipHistoryLength),
        score = score,
        subscriptionTopicSubscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),
        protocol = PubsubProtocol.Gossip_V_1_2,
        executor = executor,
        messageFactory = { DefaultPubsubMessage(it) },
        seenMessages = TTLSeenCache(SimpleSeenCache(), params.seenTTL, currentTimeSupplier),
        messageValidator = NOP_ROUTER_VALIDATOR
    ) {

        init {
            eventBroadcaster.listeners += score
        }

        /** Total bytes still queued for delivery across all peers. */
        fun pendingOutboundBytes(): Int =
            pendingRpcParts.getQueues().values.sumOf { it.estimateMaxSerializedSize() }

        override fun processExtensions(msg: Rpc.RPC, receivedFrom: PeerHandler) {
        }
    }
}
