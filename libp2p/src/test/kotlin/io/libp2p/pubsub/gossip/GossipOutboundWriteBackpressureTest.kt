package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.*
import io.libp2p.pubsub.DeterministicFuzz.Companion.createGossipFuzzRouterFactory
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.time.Duration
import java.util.Optional
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Deterministic proof of the per-peer write accumulation described in
 * JVM_LIBP2P_QUIC_GOSSIPSUB_LEAK_FIX_PLAN.md.
 *
 * The stalled transport future is intentionally never completed. This test is expected to fail
 * against the current implementation because every publish starts another write for that peer.
 */
class GossipOutboundWriteBackpressureTest : GossipTestsBase() {

    @Test
    fun `one stalled peer write must not be superseded by another write`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = GossipParams(
            D = 2,
            DLow = 2,
            DHigh = 2,
            floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH,
            iDontWantMinMessageSizeThreshold = 0
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = GossipPeerScoreParams(isDirect = { true })
        )

        val sender = fuzz.createTestRouter(
            createGossipFuzzRouterFactory {
                RecordingGossipRouterBuilder(writePolicy).apply {
                    this.params = params
                    this.scoreParams = scoreParams
                    protocol = PubsubProtocol.Gossip_V_1_2
                }
            }
        )
        val healthyPeer = createPeer(fuzz, params, scoreParams)
        val stalledPeer = createPeer(fuzz, params, scoreParams)

        sender.router.subscribe(TOPIC)
        healthyPeer.router.subscribe(TOPIC)
        stalledPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(healthyPeer)
        sender.connectSemiDuplex(stalledPeer)

        // Drain connection setup and subscription exchange before making one peer stall.
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        val peerTopics = senderRouter.getPeerTopics().get(1, TimeUnit.SECONDS)
        assertThat(peerTopics).hasSize(2)
        peerTopics.values.forEach { topics -> assertThat(topics).containsExactly(TOPIC) }

        writePolicy.stalledPeerId = stalledPeer.peerId
        writePolicy.clearRecordedWrites(stalledPeer.peerId)
        senderRouter.enqueueRpcForTest(
            stalledPeer.peerId,
            Rpc.RPC.newBuilder()
                .setControl(
                    Rpc.ControlMessage.newBuilder()
                        .addIdontwant(Rpc.ControlIDontWant.getDefaultInstance())
                )
                .build()
        )
        fuzz.timeController.addTime(Duration.ofMillis(1))

        val publishFutures = (0 until MESSAGE_COUNT).map { sequence ->
            val future = sender.router.publish(
                newMessage(TOPIC, sequence.toLong(), "message-$sequence".toByteArray())
            )
            fuzz.timeController.addTime(Duration.ofMillis(1))
            future
        }

        // anyComplete() currently lets every public publish future complete through the healthy peer.
        publishFutures.forEach { it.get(1, TimeUnit.SECONDS) }

        assertThat(writePolicy.unresolvedWriteCount(healthyPeer.peerId)).isZero()
        val stalledWriteCount = writePolicy.unresolvedWriteCount(stalledPeer.peerId)
        assertThat(stalledWriteCount)
            .withFailMessage(
                "unresolved writes reaching the permanently stalled peer: expected 1, observed $stalledWriteCount"
            )
            .isEqualTo(1)
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId).single().control.idontwantCount)
            .isEqualTo(1)
    }

    @Test
    fun `stalled peer is reset when its outbound write misses the progress deadline`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = GossipParams(
            D = 1,
            DLow = 1,
            DHigh = 1,
            floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = GossipPeerScoreParams(isDirect = { true })
        )
        val sender = fuzz.createTestRouter(
            createGossipFuzzRouterFactory {
                RecordingGossipRouterBuilder(
                    writePolicy,
                    progressTimeout = Duration.ofMillis(10)
                ).apply {
                    this.params = params
                    this.scoreParams = scoreParams
                    protocol = PubsubProtocol.Gossip_V_1_2
                }
            }
        )
        val stalledPeer = createPeer(fuzz, params, scoreParams)

        stalledPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(stalledPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        writePolicy.stalledPeerId = stalledPeer.peerId
        writePolicy.clearRecordedWrites(stalledPeer.peerId)
        val publications = (0 until 3).map { sequence ->
            sender.router.publish(newMessage(TOPIC, sequence.toLong(), "stalled-$sequence".toByteArray()))
        }
        fuzz.timeController.addTime(Duration.ofMillis(20))

        val senderRouter = sender.router as RecordingGossipRouter
        assertThat(publications).allMatch { it.isCompletedExceptionally }
        assertThat(senderRouter.peers).isEmpty()
        assertThat(senderRouter.pendingPeerCountForTest().join()).isZero()
        assertThat(senderRouter.disconnectCount).isEqualTo(1)
        assertThat(writePolicy.unresolvedWriteCount(stalledPeer.peerId)).isZero()
        assertThat(writePolicy.failedWriteCount(stalledPeer.peerId)).isEqualTo(1)
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId)).hasSize(1)

        sender.router.publish(newMessage(TOPIC, 10, "after-timeout".toByteArray()))
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId)).hasSize(1)
    }

    @Test
    fun `successful write resets the progress deadline for the next queued write`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(fuzz, writePolicy, params, scoreParams, Duration.ofMillis(100))
        val stalledPeer = createPeer(fuzz, params, scoreParams)

        stalledPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(stalledPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        writePolicy.stalledPeerId = stalledPeer.peerId
        writePolicy.clearRecordedWrites(stalledPeer.peerId)
        senderRouter.enqueueRpcForTest(stalledPeer.peerId, controlRpc())
        fuzz.timeController.addTime(Duration.ofMillis(1))
        senderRouter.enqueueRpcForTest(stalledPeer.peerId, controlRpc())
        fuzz.timeController.addTime(Duration.ofMillis(1))

        writePolicy.completeNext(stalledPeer.peerId)
        fuzz.timeController.addTime(Duration.ofMillis(1))
        fuzz.timeController.addTime(Duration.ofMillis(98))

        assertThat(senderRouter.peers).hasSize(1)
        assertThat(writePolicy.unresolvedWriteCount(stalledPeer.peerId)).isEqualTo(1)

        fuzz.timeController.addTime(Duration.ofMillis(2))

        assertThat(senderRouter.peers).isEmpty()
        assertThat(writePolicy.failedWriteCount(stalledPeer.peerId)).isEqualTo(1)
    }

    @Test
    fun `queued RPC batches are sent sequentially and preserve ordering`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(fuzz, writePolicy, params, scoreParams, Duration.ofSeconds(1))
        val stalledPeer = createPeer(fuzz, params, scoreParams)

        stalledPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(stalledPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        writePolicy.stalledPeerId = stalledPeer.peerId
        writePolicy.clearRecordedWrites(stalledPeer.peerId)
        val first = controlRpc()
        val second = controlRpc()
        senderRouter.enqueueRpcForTest(stalledPeer.peerId, first)
        fuzz.timeController.addTime(Duration.ofMillis(1))
        senderRouter.enqueueRpcForTest(stalledPeer.peerId, second)
        val publication = sender.router.publish(newMessage(TOPIC, 1, "queued".toByteArray()))
        fuzz.timeController.addTime(Duration.ofMillis(1))

        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId)).containsExactly(first)
        writePolicy.completeNext(stalledPeer.peerId)
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId)).containsExactly(first, second)
        writePolicy.completeNext(stalledPeer.peerId)
        fuzz.timeController.addTime(Duration.ofMillis(1))

        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId))
            .hasSize(3)
            .last()
            .extracting { it.publishList.single().data.toByteArray() }
            .isEqualTo("queued".toByteArray())

        writePolicy.completeNext(stalledPeer.peerId)
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(publication).isCompleted
    }

    @Test
    fun `repeated stalled peer reconnects do not retain router state`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(fuzz, writePolicy, params, scoreParams, Duration.ofMillis(10))
        val senderRouter = sender.router as RecordingGossipRouter

        repeat(3) { cycle ->
            val stalledPeer = createPeer(fuzz, params, scoreParams)
            stalledPeer.router.subscribe(TOPIC)
            sender.connectSemiDuplex(stalledPeer)
            fuzz.timeController.addTime(Duration.ofSeconds(2))

            writePolicy.stalledPeerId = stalledPeer.peerId
            writePolicy.clearRecordedWrites(stalledPeer.peerId)
            val publication = sender.router.publish(newMessage(TOPIC, cycle.toLong(), "cycle-$cycle".toByteArray()))
            fuzz.timeController.addTime(Duration.ofMillis(20))

            assertThat(publication).isCompletedExceptionally
            assertThat(senderRouter.peers).isEmpty()
            assertThat(senderRouter.pendingPeerCountForTest().join()).isZero()
            assertThat(writePolicy.failedWriteCount(stalledPeer.peerId)).isEqualTo(1)
        }
    }

    private fun createSender(
        fuzz: DeterministicFuzz,
        writePolicy: WritePolicy,
        params: GossipParams,
        scoreParams: GossipScoreParams,
        progressTimeout: Duration
    ): TestRouter = fuzz.createTestRouter(
        createGossipFuzzRouterFactory {
            RecordingGossipRouterBuilder(writePolicy, progressTimeout).apply {
                this.params = params
                this.scoreParams = scoreParams
                protocol = PubsubProtocol.Gossip_V_1_2
            }
        }
    )

    private fun singlePeerParams() = GossipParams(
        D = 1,
        DLow = 1,
        DHigh = 1,
        floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH
    )

    private fun directPeerScoreParams() = GossipScoreParams(
        peerScoreParams = GossipPeerScoreParams(isDirect = { true })
    )

    private fun controlRpc(): Rpc.RPC = Rpc.RPC.newBuilder()
        .setControl(
            Rpc.ControlMessage.newBuilder()
                .addIdontwant(Rpc.ControlIDontWant.getDefaultInstance())
        )
        .build()

    private fun createPeer(
        fuzz: DeterministicFuzz,
        params: GossipParams,
        scoreParams: GossipScoreParams
    ): TestRouter = fuzz.createTestRouter(
        createGossipFuzzRouterFactory {
            GossipRouterBuilder(
                protocol = PubsubProtocol.Gossip_V_1_2,
                params = params,
                scoreParams = scoreParams
            )
        }
    )

    private class WritePolicy {
        var stalledPeerId: PeerId? = null
        private val unresolvedWrites = mutableMapOf<PeerId, MutableList<CompletableFuture<Unit>>>()
        private val sentMessages = mutableMapOf<PeerId, MutableList<Rpc.RPC>>()

        fun send(peerId: PeerId, msg: Rpc.RPC): CompletableFuture<Unit> {
            sentMessages.getOrPut(peerId) { mutableListOf() } += msg
            if (peerId != stalledPeerId) return CompletableFuture.completedFuture(Unit)
            return CompletableFuture<Unit>().also {
                unresolvedWrites.getOrPut(peerId) { mutableListOf() } += it
            }
        }

        fun unresolvedWriteCount(peerId: PeerId): Int = unresolvedWrites[peerId]?.count { !it.isDone } ?: 0
        fun failedWriteCount(peerId: PeerId): Int =
            unresolvedWrites[peerId]?.count { it.isCompletedExceptionally } ?: 0

        fun messagesSentTo(peerId: PeerId): List<Rpc.RPC> = sentMessages[peerId] ?: emptyList()

        fun clearRecordedWrites(peerId: PeerId) {
            unresolvedWrites.remove(peerId)
            sentMessages.remove(peerId)
        }

        fun completeNext(peerId: PeerId) {
            unresolvedWrites[peerId]?.firstOrNull { !it.isDone }?.complete(Unit)
                ?: throw AssertionError("No pending write for $peerId")
        }
    }

    private class RecordingGossipRouter(
        params: GossipParams,
        scoreParams: GossipScoreParams,
        currentTimeSupplier: CurrentTimeSupplier,
        random: Random,
        name: String,
        mCache: MCache,
        score: GossipScore,
        subscriptionTopicSubscriptionFilter: TopicSubscriptionFilter,
        protocol: PubsubProtocol,
        executor: ScheduledExecutorService,
        messageFactory: PubsubMessageFactory,
        seenMessages: SeenCache<Optional<io.libp2p.core.pubsub.ValidationResult>>,
        messageValidator: PubsubRouterMessageValidator,
        private val writePolicy: WritePolicy
    ) : GossipRouter(
        params = params,
        scoreParams = scoreParams,
        currentTimeSupplier = currentTimeSupplier,
        random = random,
        name = name,
        mCache = mCache,
        score = score,
        gossipExtensionsConfig = GossipExtensionsConfig(),
        subscriptionTopicSubscriptionFilter = subscriptionTopicSubscriptionFilter,
        protocol = protocol,
        executor = executor,
        messageFactory = messageFactory,
        seenMessages = seenMessages,
        messageValidator = messageValidator
    ) {
        var disconnectCount = 0
            private set

        init {
            eventBroadcaster.listeners += object : GossipRouterEventListener {
                override fun notifyDisconnected(peerId: PeerId) {
                    disconnectCount++
                }

                override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {}
                override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {}
                override fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>) {}
                override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) {}
                override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {}
                override fun notifyMeshed(peerId: PeerId, topic: Topic) {}
                override fun notifyPruned(peerId: PeerId, topic: Topic) {}
                override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {}
            }
        }

        override fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> =
            writePolicy.send(peer.peerId, msg)

        fun enqueueRpcForTest(peerId: PeerId, msg: Rpc.RPC): CompletableFuture<Unit> =
            submitOnEventThread { enqueueRpc(peers.single { it.peerId == peerId }, msg) }

        fun pendingPeerCountForTest(): CompletableFuture<Int> =
            submitOnEventThread { pendingRpcParts.pendingPeers.size }
    }

    private class RecordingGossipRouterBuilder(
        private val writePolicy: WritePolicy,
        progressTimeout: Duration = DEFAULT_OUTBOUND_WRITE_PROGRESS_TIMEOUT
    ) : GossipRouterBuilder() {
        init {
            outboundWriteProgressTimeout = progressTimeout
        }

        override fun createGossipRouter(): GossipRouter {
            val gossipScore = scoreFactory(
                scoreParams,
                scheduledAsyncExecutor,
                currentTimeSupplier
            ) { gossipRouterEventListeners += it }

            return RecordingGossipRouter(
                params = params,
                scoreParams = scoreParams,
                currentTimeSupplier = currentTimeSupplier,
                random = random,
                name = name,
                mCache = mCache,
                score = gossipScore,
                subscriptionTopicSubscriptionFilter = subscriptionTopicSubscriptionFilter,
                protocol = protocol,
                executor = scheduledAsyncExecutor,
                messageFactory = messageFactory,
                seenMessages = seenCache,
                messageValidator = messageValidator,
                writePolicy = writePolicy
            ).also {
                it.configureOutboundWriteProgressTimeout(outboundWriteProgressTimeout)
                it.eventBroadcaster.listeners += gossipRouterEventListeners
            }
        }
    }

    private companion object {
        const val TOPIC = "backpressure-topic"
        const val MESSAGE_COUNT = 8
    }
}
