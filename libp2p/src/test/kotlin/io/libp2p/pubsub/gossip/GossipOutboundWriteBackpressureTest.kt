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
 */
class GossipOutboundWriteBackpressureTest : GossipTestsBase() {

    @Test
    fun `v1_3 initial control extensions are flushed before progress deadline`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            scoreParams,
            Duration.ofMillis(10),
            protocol = PubsubProtocol.Gossip_V_1_3
        )
        val peer = createPeer(
            fuzz,
            params,
            scoreParams,
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        sender.connectSemiDuplex(peer)
        fuzz.timeController.addTime(Duration.ofMillis(20))

        val senderRouter = sender.router as RecordingGossipRouter
        assertThat(senderRouter.resetCount(peer.peerId)).isZero()
        assertThat(writePolicy.messagesSentTo(peer.peerId))
            .anyMatch { it.hasControl() && it.control.hasExtensions() }
    }

    @Test
    fun `unwritable peer is not materialized or written until transition`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            scoreParams,
            Duration.ofSeconds(1)
        )
        val peer = createPeer(fuzz, params, scoreParams)

        peer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(peer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        writePolicy.setWritable(peer.peerId, false)
        writePolicy.clearRecordedWrites(peer.peerId)
        val publications = (1L..3L).map { sequence ->
            sender.router.publish(
                newMessage(TOPIC, sequence, "queued-$sequence".toByteArray())
            )
        }
        fuzz.timeController.addTime(Duration.ofMillis(1))

        assertThat(writePolicy.messagesSentTo(peer.peerId)).isEmpty()
        assertThat(publications).allMatch { !it.isDone }

        writePolicy.setWritable(peer.peerId, true)
        senderRouter.fireWritabilityChanged(peer.peerId)
        fuzz.timeController.addTime(Duration.ofMillis(1))

        assertThat(writePolicy.messagesSentTo(peer.peerId))
            .singleElement()
            .extracting { rpc -> rpc.publishList.map { it.data.toStringUtf8() } }
            .isEqualTo(listOf("queued-1", "queued-2", "queued-3"))
        assertThat(publications).allMatch {
            it.isDone && !it.isCompletedExceptionally
        }
    }

    @Test
    fun `deadline expires while queued work waits for writability`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            scoreParams,
            Duration.ofMillis(10)
        )
        val peer = createPeer(fuzz, params, scoreParams)

        peer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(peer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        writePolicy.setWritable(peer.peerId, false)
        writePolicy.clearRecordedWrites(peer.peerId)
        val publication = sender.router.publish(
            newMessage(TOPIC, 1, "queued".toByteArray())
        )
        fuzz.timeController.addTime(Duration.ofMillis(20))

        assertThat(publication).isCompletedExceptionally
        assertThat(writePolicy.messagesSentTo(peer.peerId)).isEmpty()
        assertThat(senderRouter.resetCount(peer.peerId)).isEqualTo(1)
        assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
    }

    @Test
    fun `materialization failure settles active generation and clears state`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams().copy(maxGossipMessageSize = 1024)
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            directPeerScoreParams(),
            Duration.ofSeconds(10)
        )
        val peer = createPeer(fuzz, params, directPeerScoreParams())

        peer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(peer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val oversizedMessage = newProtoMessage(TOPIC, 1, ByteArray(2048))
        val senderRouter = sender.router as RecordingGossipRouter
        val publication = senderRouter.enqueueOversizedPublishForTest(
            peer.peerId,
            oversizedMessage
        )

        assertThat(publication).isCompletedExceptionally
        assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
    }

    @Test
    fun `entry overflow resets peer and settles every promise`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams().copy(maxGossipMessageSize = 1024)
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            directPeerScoreParams(),
            Duration.ofSeconds(10),
            maxBytes = 4096,
            maxEntries = 8
        )
        val slowPeer = createPeer(fuzz, params, directPeerScoreParams())

        slowPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(slowPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        writePolicy.stalledPeerId = slowPeer.peerId
        val publications = (0 until 8).map {
            sender.router.publish(newMessage(TOPIC, it.toLong(), byteArrayOf(it.toByte())))
        }
        fuzz.timeController.addTime(Duration.ofMillis(1))

        val senderRouter = sender.router as RecordingGossipRouter
        assertThat(senderRouter.resetCount(slowPeer.peerId)).isEqualTo(1)
        assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
        assertThat(publications).allMatch { it.isDone }
        assertThat(publications).anyMatch { future ->
            future.handle { _, error ->
                generateSequence(error) { it.cause }
                    .any { it is OutboundQueueOverflowException }
            }.join()
        }
    }

    @Test
    fun `active and pending bytes share one limit`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams().copy(maxGossipMessageSize = 256)
        val firstMessage = newMessage(TOPIC, 1, ByteArray(16))
        val onePublishBytes = Rpc.RPC.newBuilder()
            .addPublish(firstMessage.protobufMessage)
            .build()
            .serializedSize
        val byteLimit = params.maxGossipMessageSize.toLong()
        val publicationCount = (byteLimit / onePublishBytes + 1).toInt()
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            directPeerScoreParams(),
            Duration.ofSeconds(10),
            maxBytes = byteLimit,
            maxEntries = 100
        )
        val slowPeer = createPeer(fuzz, params, directPeerScoreParams())

        slowPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(slowPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))
        writePolicy.stalledPeerId = slowPeer.peerId

        repeat(publicationCount) { sequence ->
            sender.router.publish(
                newMessage(TOPIC, sequence.toLong() + 1, ByteArray(16))
            )
            fuzz.timeController.addTime(Duration.ofMillis(1))
        }

        assertThat((sender.router as RecordingGossipRouter).resetCount(slowPeer.peerId))
            .isEqualTo(1)
    }

    @Test
    fun `control-only backlog is bounded by entries`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams().copy(maxGossipMessageSize = 1024)
        val sender = createSender(
            fuzz,
            writePolicy,
            params,
            directPeerScoreParams(),
            Duration.ofSeconds(10),
            maxBytes = 4096,
            maxEntries = 5
        )
        val slowPeer = createPeer(fuzz, params, directPeerScoreParams())

        slowPeer.router.subscribe(TOPIC)
        sender.connectSemiDuplex(slowPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        writePolicy.stalledPeerId = slowPeer.peerId
        writePolicy.clearRecordedWrites(slowPeer.peerId)
        repeat(3) {
            senderRouter.enqueueRpcForTest(slowPeer.peerId, controlRpc())
            fuzz.timeController.addTime(Duration.ofMillis(1))
        }

        assertThat(senderRouter.resetCount(slowPeer.peerId)).isEqualTo(1)
        assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
        assertThat(writePolicy.messagesSentTo(slowPeer.peerId)).hasSize(1)
    }

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

        val publishedMessages = (0 until MESSAGE_COUNT).map { sequence ->
            newMessage(TOPIC, sequence.toLong(), "message-$sequence".toByteArray())
        }
        val publishFutures = publishedMessages.map { message ->
            val future = sender.router.publish(message)
            fuzz.timeController.addTime(Duration.ofMillis(1))
            future
        }

        // anyComplete() currently lets every public publish future complete through the healthy peer.
        publishFutures.forEach { it.get(1, TimeUnit.SECONDS) }

        assertThat(writePolicy.unresolvedWriteCount(healthyPeer.peerId)).isZero()
        assertThat(healthyPeer.inboundMessages).containsExactlyElementsOf(publishedMessages)
        val stalledWriteCount = writePolicy.unresolvedWriteCount(stalledPeer.peerId)
        assertThat(stalledWriteCount)
            .withFailMessage(
                "unresolved writes reaching the permanently stalled peer: expected 1, observed $stalledWriteCount"
            )
            .isEqualTo(1)
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId).single().control.idontwantCount)
            .isEqualTo(1)

        val reply = newMessage(TOPIC, 100, "healthy-reply".toByteArray())
        healthyPeer.router.publish(reply)
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(sender.inboundMessages).containsExactly(reply)
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
        assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
        assertThat(senderRouter.disconnectCount).isEqualTo(1)
        assertThat(senderRouter.resetCount(stalledPeer.peerId)).isEqualTo(1)
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
        val publications = (1L..3L).map { sequence ->
            sender.router.publish(newMessage(TOPIC, sequence, "queued-$sequence".toByteArray()))
        }
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
            .extracting { rpc -> rpc.publishList.map { it.data.toStringUtf8() } }
            .isEqualTo(listOf("queued-1", "queued-2", "queued-3"))

        writePolicy.completeNext(stalledPeer.peerId)
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(publications).allMatch { it.isDone && !it.isCompletedExceptionally }
    }

    @Test
    fun `disconnect fails queued writes and prevents any later send`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(fuzz, writePolicy, params, scoreParams, Duration.ofSeconds(1))
        val stalledPeer = createPeer(fuzz, params, scoreParams)

        stalledPeer.router.subscribe(TOPIC)
        val connection = sender.connectSemiDuplex(stalledPeer)
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val senderRouter = sender.router as RecordingGossipRouter
        writePolicy.stalledPeerId = stalledPeer.peerId
        writePolicy.clearRecordedWrites(stalledPeer.peerId)
        val publications = (0 until 3).map { sequence ->
            sender.router.publish(newMessage(TOPIC, sequence.toLong(), "disconnect-$sequence".toByteArray()))
        }
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId)).hasSize(1)

        connection.disconnect()
        fuzz.timeController.addTime(Duration.ofMillis(1))

        assertThat(publications).allMatch { it.isCompletedExceptionally }
        assertThat(senderRouter.peers).isEmpty()
        assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
        assertThat(senderRouter.resetCount(stalledPeer.peerId)).isZero()
        assertThat(writePolicy.failedWriteCount(stalledPeer.peerId)).isEqualTo(1)

        sender.router.publish(newMessage(TOPIC, 10, "after-disconnect".toByteArray()))
        fuzz.timeController.addTime(Duration.ofMillis(1))
        assertThat(writePolicy.messagesSentTo(stalledPeer.peerId)).hasSize(1)
    }

    @Test
    fun `repeated stalled peer reconnects do not retain router state`() {
        val fuzz = DeterministicFuzz()
        val writePolicy = WritePolicy()
        val params = singlePeerParams()
        val scoreParams = directPeerScoreParams()
        val sender = createSender(fuzz, writePolicy, params, scoreParams, Duration.ofMillis(10))
        val senderRouter = sender.router as RecordingGossipRouter
        val peerKeyPair = createPeer(fuzz, params, scoreParams).keyPair
        var stalledPeerId: PeerId? = null

        repeat(3) { cycle ->
            val stalledPeer = createPeer(fuzz, params, scoreParams).also { it.keyPair = peerKeyPair }
            stalledPeerId = stalledPeerId ?: stalledPeer.peerId
            assertThat(stalledPeer.peerId).isEqualTo(stalledPeerId)
            stalledPeer.router.subscribe(TOPIC)
            sender.connectSemiDuplex(stalledPeer)
            fuzz.timeController.addTime(Duration.ofSeconds(2))

            writePolicy.stalledPeerId = stalledPeer.peerId
            writePolicy.clearRecordedWrites(stalledPeer.peerId)
            val publication = sender.router.publish(newMessage(TOPIC, cycle.toLong(), "cycle-$cycle".toByteArray()))
            fuzz.timeController.addTime(Duration.ofMillis(20))

            assertThat(publication).isCompletedExceptionally
            assertThat(senderRouter.peers).isEmpty()
            assertThat(senderRouter.hasNoOutboundStateForTest().join()).isTrue()
            assertThat(writePolicy.failedWriteCount(stalledPeer.peerId)).isEqualTo(1)
            assertThat(senderRouter.resetCount(stalledPeer.peerId)).isEqualTo(cycle + 1)
        }
    }

    private fun createSender(
        fuzz: DeterministicFuzz,
        writePolicy: WritePolicy,
        params: GossipParams,
        scoreParams: GossipScoreParams,
        progressTimeout: Duration,
        maxBytes: Long? = null,
        maxEntries: Int = DEFAULT_MAX_OUTBOUND_RETAINED_ENTRIES_PER_PEER,
        protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_2
    ): TestRouter = fuzz.createTestRouter(
        createGossipFuzzRouterFactory {
            RecordingGossipRouterBuilder(writePolicy, progressTimeout).apply {
                this.params = params
                this.scoreParams = scoreParams
                this.protocol = protocol
                maxOutboundRetainedBytesPerPeer = maxBytes
                maxOutboundRetainedEntriesPerPeer = maxEntries
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
        scoreParams: GossipScoreParams,
        protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_2
    ): TestRouter = fuzz.createTestRouter(
        createGossipFuzzRouterFactory {
            GossipRouterBuilder(
                protocol = protocol,
                params = params,
                scoreParams = scoreParams
            )
        }
    )

    private class WritePolicy {
        var stalledPeerId: PeerId? = null
        private val unwritablePeers = mutableSetOf<PeerId>()
        private val unresolvedWrites = mutableMapOf<PeerId, MutableList<CompletableFuture<Unit>>>()
        private val sentMessages = mutableMapOf<PeerId, MutableList<Rpc.RPC>>()

        fun setWritable(peerId: PeerId, writable: Boolean) {
            if (writable) {
                unwritablePeers -= peerId
            } else {
                unwritablePeers += peerId
            }
        }

        fun isWritable(peerId: PeerId): Boolean = peerId !in unwritablePeers

        fun record(peerId: PeerId, msg: Rpc.RPC) {
            sentMessages.getOrPut(peerId) { mutableListOf() } += msg
        }

        fun shouldStall(peerId: PeerId): Boolean = peerId == stalledPeerId

        fun newStalledWrite(peerId: PeerId): CompletableFuture<Unit> =
            CompletableFuture<Unit>().also {
                unresolvedWrites.getOrPut(peerId) { mutableListOf() } += it
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
        private val resetCounts = mutableMapOf<PeerId, Int>()

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

        override fun send(peer: PeerHandler, msg: Rpc.RPC): CompletableFuture<Unit> {
            writePolicy.record(peer.peerId, msg)
            return if (writePolicy.shouldStall(peer.peerId)) {
                writePolicy.newStalledWrite(peer.peerId)
            } else {
                super.send(peer, msg)
            }
        }

        override fun isOutboundWritable(peer: PeerHandler): Boolean =
            writePolicy.isWritable(peer.peerId)

        override fun resetOutboundStream(peer: PeerHandler) {
            resetCounts[peer.peerId] = resetCount(peer.peerId) + 1
            super.resetOutboundStream(peer)
        }

        fun fireWritabilityChanged(peerId: PeerId) {
            val handler = peers.single { it.peerId == peerId }.getOutboundHandler()!!
            handler.channelWritabilityChanged(handler.ctx!!)
        }

        fun enqueueRpcForTest(peerId: PeerId, msg: Rpc.RPC): CompletableFuture<Unit> =
            submitOnEventThread { enqueueRpc(peers.single { it.peerId == peerId }, msg) }

        fun enqueueOversizedPublishForTest(
            peerId: PeerId,
            msg: Rpc.Message
        ): CompletableFuture<Unit> {
            val promise = CompletableFuture<Unit>()
            submitOnEventThread {
                val pending = pendingRpcParts.get(peers.single { it.peerId == peerId })
                pending.queue.addPublish(msg)
                pending.promises += promise
                pending.usage = OutboundResourceUsage(1, 1)
                try {
                    flushPending(peers.single { it.peerId == peerId })
                } catch (_: Throwable) {
                    // Let the test inspect the promise and router state after the service-boundary failure.
                }
            }.join()
            return promise
        }

        fun hasNoOutboundStateForTest(): CompletableFuture<Boolean> =
            submitOnEventThread { !hasOutboundState() }

        fun resetCount(peerId: PeerId): Int = resetCounts[peerId] ?: 0
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
            )
        }
    }

    private companion object {
        const val TOPIC = "backpressure-topic"
        const val MESSAGE_COUNT = 8
    }
}
