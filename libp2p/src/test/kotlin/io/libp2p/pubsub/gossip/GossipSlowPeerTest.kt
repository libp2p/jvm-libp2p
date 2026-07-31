package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.NonCompleteException
import io.libp2p.etc.types.getX
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzz.Companion.createGossipFuzzRouterFactory
import io.libp2p.pubsub.DroppedRpcPartsException
import io.libp2p.pubsub.NOP_ROUTER_VALIDATOR
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.SimpleSeenCache
import io.libp2p.pubsub.TTLSeenCache
import io.libp2p.pubsub.TestRouter
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.TopicSubscriptionFilter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.Optional
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

class GossipSlowPeerTest : GossipTestsBase() {

    @Test
    fun `slow gossip peer is downscored and its queued bulk parts are dropped`() {
        val fuzz = DeterministicFuzz()
        val params = GossipParams(
            floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH,
            slowPeerPendingBytesThreshold = 1,
            slowPeerHeartbeatThreshold = 1
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = GossipPeerScoreParams(
                behaviourPenaltyWeight = -1.0,
                behaviourPenaltyThreshold = 0.0
            )
        )
        val routerFactory = createGossipFuzzRouterFactory {
            GossipRouterBuilder(
                protocol = PubsubProtocol.Gossip_V_1_1,
                params = params,
                scoreParams = scoreParams,
                enabledGossipExtensions = emptyList()
            )
        }
        val router1 = fuzz.createTestRouter(routerFactory)
        val router2 = fuzz.createTestRouter(routerFactory)
        val connection = router1.connectSemiDuplex(router2, pubsubLogs = LogLevel.ERROR)
        val gossipRouter1 = router1.router as GossipRouter
        val gossipRouter2 = router2.router as GossipRouter

        gossipRouter2.subscribe("topic1")
        fuzz.timeController.addTime(100.millis)
        connection.conn1.ch1.setWritableForTest(false)

        val scoreBeforeSlowPeer = gossipRouter1.score.score(router2.peerId)
        val publishFuture = gossipRouter1.publish(newMessage("topic1", 0L, "Hello".toByteArray()))
        fuzz.timeController.addTime(100.millis)

        assertThat(publishFuture).isNotDone()

        fuzz.timeController.addTime(1.seconds)

        assertThat(publishFuture).isCompletedExceptionally
        val exception = assertThrows(NonCompleteException::class.java) { publishFuture.getX() }
        assertThat(exception.cause).isInstanceOf(DroppedRpcPartsException::class.java)
        assertThat(gossipRouter1.score.score(router2.peerId)).isLessThan(scoreBeforeSlowPeer)
        assertThat(connection.conn1.ch1.isOpen).isTrue()
        assertThat(connection.conn1.ch2.isOpen).isTrue()
        assertThat(connection.conn2.ch1.isOpen).isTrue()
        assertThat(connection.conn2.ch2.isOpen).isTrue()
    }

    @Test
    fun `notifySlowPeer is called after consecutive heartbeats above pending queue threshold`() {
        val fuzz = DeterministicFuzz()
        val params = GossipParams(
            floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH,
            slowPeerPendingBytesThreshold = 1,
            slowPeerHeartbeatThreshold = 2
        )
        val gossipRouter = fuzz.createSlowPeerTrackingRouter(params)
        val mockRouter = fuzz.createMockRouter()
        val connection = gossipRouter.connectSemiDuplex(mockRouter, pubsubLogs = LogLevel.ERROR)
        val router = gossipRouter.router as SlowPeerTrackingGossipRouter
        val listener = SlowPeerTrackingListener()
        router.eventBroadcaster.listeners += listener
        val outboundChannel = connection.conn1.ch1

        mockRouter.router.subscribe("topic1")
        fuzz.timeController.addTime(100.millis)
        outboundChannel.setWritableForTest(false)

        router.publish(newMessage("topic1", 0L, "Hello".toByteArray()))
        fuzz.timeController.addTime(100.millis)

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).isEmpty()

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId)
        assertThat(listener.slowPeers).containsExactly(mockRouter.peerId)

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId)
        assertThat(listener.slowPeers).containsExactly(mockRouter.peerId)

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId)
        assertThat(listener.slowPeers).containsExactly(mockRouter.peerId)
    }

    @Test
    fun `slow peer pressure resets after queue shrinks below threshold`() {
        val fuzz = DeterministicFuzz()
        val params = GossipParams(
            floodPublishMaxMessageSizeThreshold = ALWAYS_FLOOD_PUBLISH,
            slowPeerPendingBytesThreshold = 1,
            slowPeerHeartbeatThreshold = 2
        )
        val gossipRouter = fuzz.createSlowPeerTrackingRouter(params)
        val mockRouter = fuzz.createMockRouter()
        val connection = gossipRouter.connectSemiDuplex(mockRouter, pubsubLogs = LogLevel.ERROR)
        val router = gossipRouter.router as SlowPeerTrackingGossipRouter
        val outboundChannel = connection.conn1.ch1

        mockRouter.router.subscribe("topic1")
        fuzz.timeController.addTime(100.millis)
        outboundChannel.setWritableForTest(false)

        router.publish(newMessage("topic1", 0L, "Hello-1".toByteArray()))
        fuzz.timeController.addTime(100.millis)
        fuzz.timeController.addTime(2.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId)

        outboundChannel.setWritableForTest(true)
        outboundChannel.runPendingTasks()
        fuzz.timeController.addTime(1.seconds)

        outboundChannel.setWritableForTest(false)
        router.publish(newMessage("topic1", 1L, "Hello-2".toByteArray()))
        fuzz.timeController.addTime(100.millis)
        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId)

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId, mockRouter.peerId)
    }

    private fun DeterministicFuzz.createSlowPeerTrackingRouter(params: GossipParams): TestRouter {
        return createTestRouter { executor, currentTimeSupplier, random ->
            val score = DefaultGossipScore(GossipScoreParams(), executor, currentTimeSupplier)
            SlowPeerTrackingGossipRouter(
                params = params,
                scoreParams = GossipScoreParams(),
                currentTimeSupplier = currentTimeSupplier,
                random = random,
                executor = executor,
                score = score
            )
        }
    }

    private class SlowPeerTrackingGossipRouter(
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
        name = "SlowPeerTrackingGossipRouter",
        mCache = MCache(params.gossipSize, params.gossipHistoryLength),
        score = score,
        subscriptionTopicSubscriptionFilter = TopicSubscriptionFilter.AllowAllTopicSubscriptionFilter(),
        protocol = PubsubProtocol.Gossip_V_1_2,
        executor = executor,
        messageFactory = { DefaultPubsubMessage(it) },
        seenMessages = TTLSeenCache(SimpleSeenCache(), params.seenTTL, currentTimeSupplier),
        messageValidator = NOP_ROUTER_VALIDATOR
    ) {

        val slowPeers = mutableListOf<PeerId>()

        init {
            eventBroadcaster.listeners += score
        }

        override fun notifySlowPeer(peer: PeerHandler) {
            super.notifySlowPeer(peer)
            slowPeers += peer.peerId
        }

        override fun processExtensions(msg: Rpc.RPC, receivedFrom: PeerHandler) {
        }
    }

    private class SlowPeerTrackingListener : GossipRouterEventListener {
        val slowPeers = mutableListOf<PeerId>()

        override fun notifyDisconnected(peerId: PeerId) {
        }

        override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {
        }

        override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {
        }

        override fun notifySeenMessage(
            peerId: PeerId,
            msg: PubsubMessage,
            validationResult: Optional<ValidationResult>
        ) {
        }

        override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) {
        }

        override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {
        }

        override fun notifyMeshed(peerId: PeerId, topic: Topic) {
        }

        override fun notifyPruned(peerId: PeerId, topic: Topic) {
        }

        override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {
        }

        override fun notifySlowPeer(peerId: PeerId) {
            slowPeers += peerId
        }
    }
}
