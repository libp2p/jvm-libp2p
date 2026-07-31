package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
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

class GossipSlowPeerTest : GossipTestsBase() {

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

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId)

        fuzz.timeController.addTime(1.seconds)
        assertThat(router.slowPeers).containsExactly(mockRouter.peerId, mockRouter.peerId)
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
            slowPeers += peer.peerId
        }

        override fun processExtensions(msg: Rpc.RPC, receivedFrom: PeerHandler) {
        }
    }
}
