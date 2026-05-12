package io.libp2p.pubsub.gossip.extensions

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.core.dsl.host
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.Validator
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.Gossip
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import io.libp2p.pubsub.gossip.partialmessages.PublishAction
import io.libp2p.pubsub.gossip.partialmessages.PublishActionsFn
import io.libp2p.security.noise.NoiseXXSecureChannel
import io.libp2p.transport.tcp.TcpTransport
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import io.libp2p.core.pubsub.Topic as ApiTopic

/**
 * Mixed-peer interop test (Step 10).
 *
 * Three real libp2p hosts on the same topic:
 *  - nodeA: partial-capable (requests + supports partial)
 *  - nodeB: partial-capable (requests + supports partial)
 *  - nodeC: non-partial (Gossip v1.3 without PARTIAL_MESSAGES extension)
 *
 * Topology: A—B and A—C (star, A is the hub).
 *
 * Key assertions:
 *  1. Full message from A is suppressed for B (partial), delivered to C (non-partial).
 *  2. Partial RPC from A reaches B but not C.
 *  3. Full message from C (non-partial sender) is received by A even though A is partial-capable;
 *     non-partial senders cannot honour the partial-request and send full messages unconditionally.
 */
class PartialMessagesMixedPeerTest {

    private val topic = "mixed-peer-topic"
    private val groupId = "group-mixed".toByteArray()

    private val nodeBPartialRpcs = CopyOnWriteArrayList<Rpc.PartialMessagesExtension>()
    private val nodeBFullMessages = CopyOnWriteArrayList<ByteArray>()
    private val nodeCFullMessages = CopyOnWriteArrayList<ByteArray>()
    private val nodeAFullMessages = CopyOnWriteArrayList<ByteArray>()

    private fun bHandler(): PartialMessagesHandler<ByteArray> =
        object : PartialMessagesHandler<ByteArray> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, ByteArray>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ): ByteArray? {
                nodeBPartialRpcs += rpc
                return null
            }

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, ByteArray>,
                feedback: PartialMessagesPeerFeedback,
            ) {}
        }

    private fun buildPartialRouter(handler: PartialMessagesHandler<ByteArray>) =
        GossipRouterBuilder(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
            partialMessagesHandler = handler,
        ).build()

    private fun buildNonPartialRouter() =
        GossipRouterBuilder(
            protocol = PubsubProtocol.Gossip_V_1_3,
        ).build()

    private val routerA by lazy { buildPartialRouter(nopPartialMessagesHandler) }
    private val routerB by lazy { buildPartialRouter(bHandler()) }
    private val routerC by lazy { buildNonPartialRouter() }

    private val gossipA by lazy { Gossip(routerA) }
    private val gossipB by lazy { Gossip(routerB) }
    private val gossipC by lazy { Gossip(routerC) }

    private fun buildHost(gossip: Gossip) = host {
        identity { random() }
        transports { add(::TcpTransport) }
        network { listen("/ip4/127.0.0.1/tcp/0") }
        secureChannels { add(::NoiseXXSecureChannel) }
        muxers { +StreamMuxerProtocol.Mplex }
        protocols { +gossip }
    }

    private val hostA by lazy { buildHost(gossipA) }
    private val hostB by lazy { buildHost(gossipB) }
    private val hostC by lazy { buildHost(gossipC) }

    @BeforeEach
    fun setUp() {
        hostA.start().get(5, TimeUnit.SECONDS)
        hostB.start().get(5, TimeUnit.SECONDS)
        hostC.start().get(5, TimeUnit.SECONDS)
    }

    @AfterEach
    fun tearDown() {
        hostA.stop().get(5, TimeUnit.SECONDS)
        hostB.stop().get(5, TimeUnit.SECONDS)
        hostC.stop().get(5, TimeUnit.SECONDS)
    }

    /**
     * Connects the three hosts and waits for all handshakes to settle:
     *  - ControlExtensions exchanged between A↔B and A↔C
     *  - SubOpts with partial flags from B→A and A→B
     */
    private fun connectMixedNetwork(): Triple<PeerId, PeerId, PeerId> {
        // Partial flags must be set before subscribing so they are included in the
        // SubOpts sent on peer activation (onPeerActive → enqueueSubscribe reads them).
        routerA.setTopicPartialFlags(topic, requestsPartial = true, supportsSendingPartial = true)
        routerB.setTopicPartialFlags(topic, requestsPartial = true, supportsSendingPartial = true)

        gossipA.subscribe(
            Validator { msg ->
                nodeAFullMessages += msg.data.array().copyOf()
                CompletableFuture.completedFuture(ValidationResult.Valid)
            },
            ApiTopic(topic),
        )
        gossipB.subscribe(
            Validator { msg ->
                nodeBFullMessages += msg.data.array().copyOf()
                CompletableFuture.completedFuture(ValidationResult.Valid)
            },
            ApiTopic(topic),
        )
        gossipC.subscribe(
            Validator { msg ->
                nodeCFullMessages += msg.data.array().copyOf()
                CompletableFuture.completedFuture(ValidationResult.Valid)
            },
            ApiTopic(topic),
        )

        hostA.network.connect(hostB.peerId, hostB.listenAddresses().first()).get(10, TimeUnit.SECONDS)
        hostA.network.connect(hostC.peerId, hostC.listenAddresses().first()).get(10, TimeUnit.SECONDS)

        val peerAId = hostA.peerId
        val peerBId = hostB.peerId
        val peerCId = hostC.peerId

        // Wait for ControlExtensions handshake between A and B (both partial-capable).
        waitForOnEventThread(routerA) { routerA.gossipExtensionsState.peerSupportsPartialMessages(peerBId) }
        waitForOnEventThread(routerB) { routerB.gossipExtensionsState.peerSupportsPartialMessages(peerAId) }
        // Wait for A to have received ControlExtensions from C (non-partial — partialMessages=false).
        waitForOnEventThread(routerA) { routerA.gossipExtensionsState.hasReceivedControlExtensionsFrom(peerCId) }

        // Wait for partial SubOpts: B→A and A→B.
        waitForOnEventThread(routerA) { routerA.partialSubscriptionState.peerRequestsPartial(topic, peerBId) }
        waitForOnEventThread(routerB) { routerB.partialSubscriptionState.peerRequestsPartial(topic, peerAId) }

        return Triple(peerAId, peerBId, peerCId)
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            Thread.sleep(100)
        }
        throw TimeoutException("Timed out waiting for condition")
    }

    private fun waitForOnEventThread(router: GossipRouter, predicate: () -> Boolean) {
        waitFor { router.submitOnEventThread { predicate() }.get(1, TimeUnit.SECONDS) }
    }

    // ── Test 1: full-message suppression ────────────────────────────────────

    @Test
    fun `full message from partial node reaches non-partial peer but not partial peer`() {
        connectMixedNetwork()

        val payload = "hello mixed network".toByteArray()
        gossipA.createPublisher(hostA.privKey, 0L)
            .publish(Unpooled.wrappedBuffer(payload), ApiTopic(topic))
            .get(5, TimeUnit.SECONDS)

        // C (non-partial) MUST receive the full message.
        waitFor { nodeCFullMessages.isNotEmpty() }
        assertThat(nodeCFullMessages.first()).isEqualTo(payload)

        // B (partial, requested suppression) MUST NOT receive the full message.
        Thread.sleep(500)
        assertThat(nodeBFullMessages).isEmpty()
    }

    // ── Test 2: partial RPC delivery ────────────────────────────────────────

    @Test
    fun `partial RPC from partial node reaches partial peer`() {
        val (_, peerBId, _) = connectMixedNetwork()

        val partPayload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        val partMeta = byteArrayOf(0x01)

        gossipA.publishPartial(
            topic,
            groupId,
            PublishActionsFn<ByteArray> { _, _ ->
                sequenceOf(peerBId to PublishAction(partialMessage = partPayload, partsMetadata = partMeta))
            },
        ).get(5, TimeUnit.SECONDS)

        waitFor { nodeBPartialRpcs.isNotEmpty() }
        val rpc = nodeBPartialRpcs.single()
        assertThat(rpc.topicID).isEqualTo(topic)
        assertThat(rpc.groupID).isEqualTo(ByteString.copyFrom(groupId))
        assertThat(rpc.partialMessage.toByteArray()).isEqualTo(partPayload)
        assertThat(rpc.partsMetadata.toByteArray()).isEqualTo(partMeta)
    }

    // ── Test 3: non-partial sender can still deliver to partial-capable nodes ─

    @Test
    fun `non-partial node sends full message received by partial-capable direct peer`() {
        // C (non-partial) publishes. A (partial-capable, directly connected to C) receives
        // the full message because:
        //  - Suppression is OUTBOUND only (A suppresses when it would send TO B).
        //  - A still RECEIVES full messages from peers that don't support partial.
        connectMixedNetwork()

        val payload = "from non-partial node C".toByteArray()
        gossipC.createPublisher(hostC.privKey, 0L)
            .publish(Unpooled.wrappedBuffer(payload), ApiTopic(topic))
            .get(5, TimeUnit.SECONDS)

        waitFor { nodeAFullMessages.isNotEmpty() }
        assertThat(nodeAFullMessages.first()).isEqualTo(payload)
    }

    // ── Helper: no-op handler for nodeA ─────────────────────────────────────

    private val nopPartialMessagesHandler: PartialMessagesHandler<ByteArray> =
        object : PartialMessagesHandler<ByteArray> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, ByteArray>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback,
            ): ByteArray? = null

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, ByteArray>,
                feedback: PartialMessagesPeerFeedback,
            ) {}
        }
}
