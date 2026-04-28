package io.libp2p.simulate.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import io.libp2p.simulate.TopologyGraph
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.topology.asFixedTopology
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import kotlin.time.Duration.Companion.seconds

/**
 * Simulator scenario for Step 10: mixed partial + non-partial nodes on the same topic.
 *
 * Network topology (star, peer 0 is the hub):
 *   0 ↔ 1  (peer 1 is partial-capable)
 *   0 ↔ 2  (peer 2 is non-partial)
 *   0 ↔ 3  (peer 3 is non-partial)
 *
 * Peers 0 and 1 have [GossipExtension.PARTIAL_MESSAGES] enabled with
 * `requestsPartial = true` and `supportsSendingPartial = true` on the shared topic.
 * Peers 2 and 3 use Gossip v1.3 without the extension — they send
 * ControlExtensions with `partialMessages = false`.
 *
 * All peers use Gossip v1.3 (required for ControlExtensions / SubOpts flag wire support).
 *
 * Two routing properties are exercised:
 *  1. Full messages published by a partial-capable peer are suppressed for the partial peer (1)
 *     but reach the non-partial peers (2, 3).
 *  2. Full messages published by a non-partial peer are received by the partial-capable hub (0)
 *     and forwarded to the non-partial peer (3), but NOT to the partial peer (1) — because the
 *     partial-capable hub suppresses forwarding to partial-requesting peers in `broadcastInbound`.
 */
class PartialMessagesSimTest {

    private val topic = Topic(BlocksTopic)

    private val PARTIAL_PEER_COUNT = 2
    private val TOTAL_PEERS = 4

    // D=3 so all 3 connected peers fit comfortably in the mesh.
    private val gossipParams = Eth2DefaultGossipParams.copy(D = 3, DLow = 1, DHigh = 3, DOut = 0)
    private val gossipScoreParams = Eth2DefaultScoreParams

    private val simConfig = GossipSimConfig(
        totalPeers = TOTAL_PEERS,
        topics = listOf(topic),
        topology = TopologyGraph.customTopology(
            0 to 1,
            0 to 2,
            0 to 3,
        ).asFixedTopology(),
        warmUpDelay = 10.seconds,
    )

    private val nopHandler = object : PartialMessagesHandler<Unit> {
        override fun onIncomingRpc(
            from: PeerId,
            peerStates: Map<PeerId, Unit>,
            rpc: Rpc.PartialMessagesExtension,
            feedback: PartialMessagesPeerFeedback,
        ) {}

        override fun onEmitGossip(
            topic: io.libp2p.pubsub.Topic,
            groupId: ByteArray,
            gossipPeers: Collection<PeerId>,
            peerStates: Map<PeerId, Unit>,
            feedback: PartialMessagesPeerFeedback,
        ) {}
    }

    private fun buildSimulation(): GossipSimulation {
        val routerFactory: GossipRouterBuilderFactory = { peerIndex ->
            SimGossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
                // All peers use v1.3 so they exchange ControlExtensions.
                it.protocol = PubsubProtocol.Gossip_V_1_3
                if (peerIndex < PARTIAL_PEER_COUNT) {
                    it.enabledGossipExtensions(GossipExtension.PARTIAL_MESSAGES)
                    it.partialMessagesHandler = nopHandler
                }
            }
        }

        val simNetwork = GossipSimNetwork(simConfig, routerFactory) { peerIndex, peer ->
            if (peerIndex < PARTIAL_PEER_COUNT) {
                // Set partial flags before subscribe so they are included in the SubOpts
                // sent to peers on activation (onPeerActive → enqueueSubscribe reads them).
                peer.router.setTopicPartialFlags(
                    topic.topic,
                    requestsPartial = true,
                    supportsSendingPartial = true,
                )
            }
        }

        simNetwork.createAllPeers()
        simNetwork.connectAllPeers()
        return GossipSimulation(simConfig, simNetwork)
    }

    // ── Test 1: full-message suppression ─────────────────────────────────────

    @Test
    fun `full messages from partial node are suppressed for partial peer, delivered to non-partial peers`() {
        val simulation = buildSimulation()

        // Peer 0 (partial, supportsSendingPartial=true) publishes a full gossip message.
        simulation.publishMessage(srcPeer = 0)
        simulation.forwardTime(2.seconds)

        val result = simulation.gatherPubDeliveryStats()
        val recipientIds = result.deliveries.map { it.toPeer.simPeerId }.toSet()

        // Non-partial peers MUST receive the full message.
        assertThat(recipientIds).contains(2, 3)

        // Peer 1 (partial, requestsPartial=true, connected only to peer 0) MUST NOT
        // receive the full message — peer 0 suppresses it.
        assertThat(recipientIds).doesNotContain(1)
    }

    // ── Test 2: non-partial propagation ──────────────────────────────────────

    @Test
    fun `full messages from non-partial node propagate to non-partial peers, not to partial peer`() {
        val simulation = buildSimulation()

        // Peer 2 (non-partial) publishes a full gossip message.
        simulation.publishMessage(srcPeer = 2)
        simulation.forwardTime(2.seconds)

        val result = simulation.gatherPubDeliveryStats()
        val recipientIds = result.deliveries.map { it.toPeer.simPeerId }.toSet()

        // Peer 0 (partial-capable hub, directly connected to peer 2) MUST receive the message.
        // Non-partial senders cannot honour partial requests; they send full messages unconditionally.
        assertThat(recipientIds).contains(0)

        // Peer 3 (non-partial) MUST receive the message forwarded by peer 0.
        assertThat(recipientIds).contains(3)

        // Peer 1 (partial, connected only to peer 0) MUST NOT receive the full message
        // — peer 0 suppresses forwarding to partial-requesting peers in broadcastInbound.
        assertThat(recipientIds).doesNotContain(1)
    }
}
