package io.libp2p.pubsub.gossip

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.etc.types.hours
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.util.P2PService
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.Topic
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.util.Optional

class DefaultGossipScoreTest {

    @Test
    fun `test misbehavior score threshold`() {
        val peer = mockPeer()

        val peerScoreParams = GossipPeerScoreParams(
            decayInterval = 1.seconds,
            behaviourPenaltyWeight = -1.0,
            behaviourPenaltyDecay = 0.9,
            behaviourPenaltyThreshold = 5.0
        )
        val scoreParams = GossipScoreParams(peerScoreParams)
        val timeController = TimeControllerImpl()
        val executor = ControlledExecutorServiceImpl(timeController)

        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })

        assertEquals(0.0, score.score(peer.peerId))

        // not hit threshold yet
        score.notifyRouterMisbehavior(peer.peerId, 5)
        assertEquals(0.0, score.score(peer.peerId))

        // behaviourPenaltyThreshold reached
        score.notifyRouterMisbehavior(peer.peerId, 1)
        assertTrue(score.score(peer.peerId) < 0)

        // quadratic penalty
        score.notifyRouterMisbehavior(peer.peerId, 10)
        assertTrue(score.score(peer.peerId) < -50)

        // negative behaviour should not be forgotten so fast
        timeController.addTime(10.seconds)
        assertTrue(score.score(peer.peerId) < 0)

        // time heals
        timeController.addTime(10.minutes)
        assertEquals(0.0, score.score(peer.peerId))
    }

    @Test
    fun `test topic score`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic = "testTopic"
        val otherTopic = "randomTopic"
        val topicScoreParams = GossipTopicScoreParams(
            topicWeight = 1.0,
            timeInMeshWeight = 1.0,
            timeInMeshQuantum = 1.seconds,
            timeInMeshCap = 1.0,
            firstMessageDeliveriesWeight = 2.0,
            firstMessageDeliveriesDecay = 0.9,
            firstMessageDeliveriesCap = 4.0,
            meshMessageDeliveriesWeight = -1.0,
            meshMessageDeliveriesDecay = 0.9,
            meshMessageDeliveriesThreshold = 3.0,
            meshMessageDeliveriesCap = 5.0,
            meshMessageDeliveriesActivation = 10.seconds,
            meshMessageDeliveryWindow = 2.seconds,
            invalidMessageDeliveriesWeight = -0.5,
            invalidMessageDeliveriesDecay = .9
        )
        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })

        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // After 1 quantum of time in the mesh, we should increment the score by timeInMeshWeight
        timeController.addTime(1.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(1.0)

        // After delivering a message, we should increase our score by firstMessageDeliveriesWeight
        val msg = DefaultPubsubMessage(createRpcMessage(topic))
        score.notifyUnseenValidMessage(peer.peerId, msg)
        assertThat(score.score(peer.peerId)).isEqualTo(3.0)

        // Message for an unknown topic should be scored using default (disabled) scoring, so score should not change
        val unknownTopicMsg = DefaultPubsubMessage(createRpcMessage(otherTopic))
        score.notifyUnseenValidMessage(peer.peerId, unknownTopicMsg)
        assertThat(score.score(peer.peerId)).isEqualTo(3.0)

        // Invalid msg should decrement score
        val invalidMsg = DefaultPubsubMessage(createRpcMessage(topic, 2))
        score.notifyUnseenInvalidMessage(peer.peerId, invalidMsg)
        assertThat(score.score(peer.peerId)).isEqualTo(2.5)

        // Invalid msg for unknown topic should not decrement score
        val unknownInvalidMsg = DefaultPubsubMessage(createRpcMessage(otherTopic, 2))
        score.notifyUnseenInvalidMessage(peer.peerId, unknownInvalidMsg)
        assertThat(score.score(peer.peerId)).isEqualTo(2.5)

        // Advance time to activate low delivery penalty
        // We've delivered 1 message which is 2 below the meshDeliveriesThreshold (3)
        // Squaring this, we get a penalty of -4
        timeController.addTime(10.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(2.5 - 4.0)
    }

    @Test
    fun `test first message delivery decay`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(4.0)
            .firstMessageDeliveriesWeight(2.0)
            .firstMessageDeliveriesDecay(0.5)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // After delivering a message, we should increase our score by firstMessageDeliveriesWeight
        val msg = DefaultPubsubMessage(createRpcMessage(topic))
        score.notifyUnseenValidMessage(peer.peerId, msg)
        assertThat(score.score(peer.peerId)).isEqualTo(2.0)

        // Refresh to decay score
        score.refreshScores()
        assertThat(score.score(peer.peerId)).isEqualTo(1.0)
    }

    @Test
    fun `test getCachedScore returns expected values`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(4.0)
            .firstMessageDeliveriesWeight(2.0)
            .firstMessageDeliveriesDecay(0.5)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        // Check value before interacting with peer
        assertThat(score.getCachedScore(peer.peerId)).isEqualTo(0.0)

        // Check value after accessing score
        assertEquals(0.0, score.score(peer.peerId))
        assertThat(score.getCachedScore(peer.peerId)).isEqualTo(0.0)

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)
        assertThat(score.getCachedScore(peer.peerId)).isEqualTo(0.0)

        // After delivering a message, we should increase our score by firstMessageDeliveriesWeight
        val msg = DefaultPubsubMessage(createRpcMessage(topic))
        score.notifyUnseenValidMessage(peer.peerId, msg)
        assertThat(score.score(peer.peerId)).isEqualTo(2.0)
        assertThat(score.getCachedScore(peer.peerId)).isEqualTo(2.0)

        // Refresh to decay score
        score.refreshScores()
        assertThat(score.score(peer.peerId)).isEqualTo(1.0)
        assertThat(score.getCachedScore(peer.peerId)).isEqualTo(1.0)
    }

    @Test
    fun `test mesh message delivery decay`() {
        val peer = mockPeer()
        val otherPeer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(0.0)
            .firstMessageDeliveriesWeight(0.0)
            .firstMessageDeliveriesDecay(0.0)
            .meshMessageDeliveriesActivation(1.seconds)
            .meshMessageDeliveryWindow(2.seconds)
            .meshMessageDeliveriesDecay(0.5)
            .meshMessageDeliveriesCap(4.0)
            .meshMessageDeliveriesThreshold(3.0)
            .meshMessageDeliveriesWeight(-1.0)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Deliver first message from other peer
        val msg = DefaultPubsubMessage(createRpcMessage(topic))
        score.notifyUnseenValidMessage(otherPeer.peerId, msg)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Deliver same message from target peer
        score.notifySeenMessage(peer.peerId, msg, Optional.empty())
        // Score should not change yet because we haven't passed the activation window
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Move through activation window and check score
        // Score should be negative since we haven't met delivery threshold
        timeController.addTime(2.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(-4.0)

        // Refresh to decay score, increasing the message delivery penalty since the delivered message counter decays
        // Penalty = (3 - (1 * .5))^2
        score.refreshScores()
        assertThat(score.score(peer.peerId)).isEqualTo(-6.25)
    }

    @Test
    fun `test invalid message decay`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .invalidMessageDeliveriesDecay(0.5)
            .invalidMessageDeliveriesWeight(-2.0)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // After delivering an invalid message, we should get a penalty
        val msg = DefaultPubsubMessage(createRpcMessage(topic))
        score.notifyUnseenInvalidMessage(peer.peerId, msg)
        assertThat(score.score(peer.peerId)).isEqualTo(-2.0)

        // Refresh to decay score
        // counter 1 decays to 0.5, score = weight * counter^2 = -0.5
        score.refreshScores()
        assertThat(score.score(peer.peerId)).isEqualTo(-0.5)
    }

    @Test
    fun `update topic weight param`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .timeInMeshWeight(2.0)
            .timeInMeshQuantum(1.seconds)
            .timeInMeshCap(6.0)
            .build()
        val updatedTopicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(2.0)
            .timeInMeshWeight(2.0)
            .timeInMeshQuantum(1.seconds)
            .timeInMeshCap(6.0)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Increase time in mesh beyond max
        timeController.addTime(10.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(12.0)

        // Update params
        score.updateTopicParams(mapOf(Pair(topic, updatedTopicScoreParams)))

        // Score calculation should be updated
        assertThat(score.score(peer.peerId)).isEqualTo(24.0)
    }

    @Test
    fun `update time in mesh params`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .timeInMeshWeight(2.0)
            .timeInMeshQuantum(1.seconds)
            .timeInMeshCap(6.0)
            .build()
        val updatedTopicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .timeInMeshWeight(1.0)
            .timeInMeshQuantum(500.millis)
            .timeInMeshCap(4.0)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Increase time in mesh beyond max
        timeController.addTime(10.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(12.0)

        // Update params
        score.updateTopicParams(mapOf(Pair(topic, updatedTopicScoreParams)))

        // Score calculation should be updated
        assertThat(score.score(peer.peerId)).isEqualTo(4.0)
    }

    @Test
    fun `update first message delivery topic params`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(4.0)
            .firstMessageDeliveriesWeight(2.0)
            .firstMessageDeliveriesDecay(1.0)
            .build()
        val updatedTopicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(2.0)
            .firstMessageDeliveriesWeight(1.0)
            .firstMessageDeliveriesDecay(0.5)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Deliver several message
        for (i in 0..5) {
            val msg = DefaultPubsubMessage(createRpcMessage(topic, i))
            score.notifyUnseenValidMessage(peer.peerId, msg)
        }
        assertThat(score.score(peer.peerId)).isEqualTo(8.0)

        // Update params and check score again
        score.updateTopicParams(mapOf(Pair(topic, updatedTopicScoreParams)))
        assertThat(score.score(peer.peerId)).isEqualTo(2.0)

        // Check decay
        score.refreshScores()
        assertThat(score.score(peer.peerId)).isEqualTo(1.0)
    }

    @Test
    fun `update mesh message delivery topic params`() {
        // TODO
        val peer = mockPeer()
        val otherPeer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(0.0)
            .firstMessageDeliveriesWeight(0.0)
            .firstMessageDeliveriesDecay(0.0)
            .meshMessageDeliveriesActivation(1.seconds)
            .meshMessageDeliveryWindow(2.seconds)
            .meshMessageDeliveriesDecay(1.0)
            .meshMessageDeliveriesCap(4.0)
            .meshMessageDeliveriesThreshold(4.0)
            .meshMessageDeliveriesWeight(-1.0)
            .build()
        val updatedTopicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .firstMessageDeliveriesCap(0.0)
            .firstMessageDeliveriesWeight(0.0)
            .firstMessageDeliveriesDecay(0.0)
            .meshMessageDeliveriesActivation(2.seconds)
            .meshMessageDeliveryWindow(2.seconds)
            .meshMessageDeliveriesDecay(0.5)
            .meshMessageDeliveriesCap(2.0)
            .meshMessageDeliveriesThreshold(2.0)
            .meshMessageDeliveriesWeight(-2.0)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Deliver a bunch of invalid messages
        for (i in 0..5) {
            // Deliver first message from other peer
            val msg = DefaultPubsubMessage(createRpcMessage(topic, i))
            score.notifyUnseenValidMessage(otherPeer.peerId, msg)
            assertThat(score.score(peer.peerId)).isEqualTo(0.0)

            // Deliver same message from target peer
            score.notifySeenMessage(peer.peerId, msg, Optional.empty())
            // Score should not change yet because we haven't passed the activation window
            assertThat(score.score(peer.peerId)).isEqualTo(0.0)
        }

        // Move through activation window and check score
        // Score should be 0 since we should be at the delivery threshold (4)
        timeController.addTime(2.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Update params
        score.updateTopicParams(mapOf(Pair(topic, updatedTopicScoreParams)))

        // Refresh to decay score, deliveries should be capped at 2, and decay at rate of 0.5, leaving counter = 1.0
        score.refreshScores()
        // Score should be 0.0 since activation window has expanded
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // Move through new activation window and check score again
        timeController.addTime(1.seconds)
        assertThat(score.score(peer.peerId)).isEqualTo(-2.0)
    }

    @Test
    fun `update invalid message topic params`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topic: Topic = "testTopic"
        val topicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .invalidMessageDeliveriesDecay(1.0)
            .invalidMessageDeliveriesWeight(-2.0)
            .build()
        val updatedTopicScoreParams = GossipTopicScoreParams.builder()
            .topicWeight(1.0)
            .invalidMessageDeliveriesDecay(0.5)
            .invalidMessageDeliveriesWeight(-1.0)
            .build()

        val defaultScoreParams = GossipTopicScoreParams.builder().build()
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topic, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Add peer to mesh
        score.notifyMeshed(peer.peerId, topic)
        assertThat(score.score(peer.peerId)).isEqualTo(0.0)

        // After delivering an invalid message, we should get a penalty
        val msg = DefaultPubsubMessage(createRpcMessage(topic))
        score.notifyUnseenInvalidMessage(peer.peerId, msg)
        assertThat(score.score(peer.peerId)).isEqualTo(-2.0)

        // Update params and check score is updated
        score.updateTopicParams(mapOf(Pair(topic, updatedTopicScoreParams)))
        assertThat(score.score(peer.peerId)).isEqualTo(-1.0)

        // Refresh to decay score
        // counter 1 decays to 0.5, score = weight * counter^2 = -0.25
        score.refreshScores()
        assertThat(score.score(peer.peerId)).isEqualTo(-0.25)
    }

    @Test
    fun `update topic params for untracked topic`() {
        val peer = mockPeer()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder().build()
        val topicA = "topicA"
        val topicB = "topicB"
        val topicScoreParams = GossipTopicScoreParams(
            topicWeight = 1.0,
            timeInMeshWeight = 1.0,
            timeInMeshQuantum = 1.seconds,
            timeInMeshCap = 1.0,
            firstMessageDeliveriesWeight = 2.0,
            firstMessageDeliveriesDecay = 0.9,
            firstMessageDeliveriesCap = 4.0,
            meshMessageDeliveriesWeight = -1.0,
            meshMessageDeliveriesDecay = 0.9,
            meshMessageDeliveriesThreshold = 3.0,
            meshMessageDeliveriesCap = 5.0,
            meshMessageDeliveriesActivation = 1.seconds,
            meshMessageDeliveryWindow = 2.seconds,
            invalidMessageDeliveriesWeight = -0.5,
            invalidMessageDeliveriesDecay = .9
        )
        // Set defaults so that counters are tracked but score is 0
        val defaultScoreParams = GossipTopicScoreParams(
            topicWeight = 0.0,
            timeInMeshWeight = 1.0,
            timeInMeshQuantum = 1.seconds,
            timeInMeshCap = 1000.0,
            firstMessageDeliveriesWeight = 0.0,
            firstMessageDeliveriesDecay = 1.0,
            firstMessageDeliveriesCap = 1000.0,
            meshMessageDeliveriesWeight = 0.0,
            meshMessageDeliveriesDecay = 1.0,
            meshMessageDeliveriesThreshold = 0.0,
            meshMessageDeliveriesCap = 1000.0,
            meshMessageDeliveriesActivation = 0.seconds,
            meshMessageDeliveryWindow = 200.seconds,
            invalidMessageDeliveriesWeight = 0.0,
            invalidMessageDeliveriesDecay = 1.0
        )
        val topicsScoreParams = GossipTopicsScoreParams(defaultScoreParams, mutableMapOf(Pair(topicA, topicScoreParams)))
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            topicsScoreParams = topicsScoreParams
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        assertEquals(0.0, score.score(peer.peerId))

        // Generate same activity for topic a and b
        for (curTopic in arrayOf(topicA, topicB)) {
            // Add peer to mesh
            score.notifyMeshed(peer.peerId, curTopic)

            // Deliver a valid first message
            val msg = DefaultPubsubMessage(createRpcMessage(curTopic))
            score.notifyUnseenValidMessage(peer.peerId, msg)

            // Deliver an invalid message
            val invalidMsg = DefaultPubsubMessage(createRpcMessage(curTopic, 2))
            score.notifyUnseenInvalidMessage(peer.peerId, invalidMsg)
        }

        // Increase time in mesh past activation period
        timeController.addTime(2.seconds)

        // Score should just hold value for topicA since the default topic has params that zero-out its score
        assertThat(score.score(peer.peerId)).isEqualTo(-1.5)

        // Update params and now second topic should contribute to the score (doubling it)
        score.updateTopicParams(mapOf(Pair(topicB, topicScoreParams)))
        assertThat(score.score(peer.peerId)).isEqualTo(-3.0)
    }

    @Test
    fun `test IP colocation penalty`() {

        val addr1 = Multiaddr.fromString("/ip4/0.0.0.1")
        val addr2 = Multiaddr.fromString("/ip4/0.0.0.2")
        val peer1 = PeerId.random()
        val peer2 = PeerId.random()
        val peer3 = PeerId.random()
        val peer4 = PeerId.random()
        val peer5 = PeerId.random()

        // Setup score params with topic config
        val peerScoreParams = GossipPeerScoreParams.builder()
            .ipColocationFactorThreshold(2)
            .ipColocationFactorWeight(-1.0)
            .build()

        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
        )

        // Setup time provider - apply non-zero time so that we don't get 0-valued timestamps that may be interpreted
        // as empty
        val timeController = TimeControllerImpl()
        timeController.addTime(1.hours)
        val executor = ControlledExecutorServiceImpl(timeController)

        // Check initial value
        val score = DefaultGossipScore(scoreParams, executor, { timeController.time })
        score.notifyConnected(peer1, addr1)
        score.notifyConnected(peer2, addr1)
        assertEquals(0.0, score.score(peer1))
        assertEquals(0.0, score.score(peer2))

        score.notifyConnected(peer3, addr2)
        assertEquals(0.0, score.score(peer1))
        assertEquals(0.0, score.score(peer2))
        assertEquals(0.0, score.score(peer3))

        score.notifyConnected(peer4, addr1)

        assertEquals(-1.0, score.score(peer1))
        assertEquals(-1.0, score.score(peer2))
        assertEquals(0.0, score.score(peer3))
        assertEquals(-1.0, score.score(peer4))

        score.notifyConnected(peer5, addr1)

        assertEquals(-4.0, score.score(peer1))
        assertEquals(-4.0, score.score(peer2))
        assertEquals(0.0, score.score(peer3))
        assertEquals(-4.0, score.score(peer4))
        assertEquals(-4.0, score.score(peer5))

        score.notifyDisconnected(peer1)

        timeController.addTime(peerScoreParams.retainScore - 1.seconds)
        score.refreshScores()

        // while peer score retained its IP should participate in colocation penalty
        assertEquals(-4.0, score.score(peer2))
        assertEquals(0.0, score.score(peer3))
        assertEquals(-4.0, score.score(peer4))
        assertEquals(-4.0, score.score(peer5))

        timeController.addTime(2.seconds)
        score.refreshScores()

        assertEquals(-1.0, score.score(peer2))
        assertEquals(0.0, score.score(peer3))
        assertEquals(-1.0, score.score(peer4))
        assertEquals(-1.0, score.score(peer5))

        score.notifyDisconnected(peer2)

        timeController.addTime(peerScoreParams.retainScore + 1.seconds)
        score.refreshScores()

        assertEquals(0.0, score.score(peer3))
        assertEquals(0.0, score.score(peer4))
        assertEquals(0.0, score.score(peer5))

        score.notifyConnected(peer1, addr1)

        assertEquals(-1.0, score.score(peer1))
        assertEquals(0.0, score.score(peer3))
        assertEquals(-1.0, score.score(peer4))
        assertEquals(-1.0, score.score(peer5))
    }

    private fun createRpcMessage(topic: String, seqNo: Int = 1): Rpc.Message {
        return Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(ByteString.copyFrom("Test", StandardCharsets.US_ASCII))
            .build()
    }

    private fun mockPeer(): P2PService.PeerHandler {
        val peerId = PeerId.random()
        val peer = mockk<P2PService.PeerHandler>()
        every { peer.peerId } returns peerId
        every { peer.getRemoteAddress() } returns Multiaddr.fromString("/ip4/127.0.0.1")
        return peer
    }
}
