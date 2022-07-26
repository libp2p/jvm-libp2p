package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.RESULT_IGNORE
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzz.Companion.createGossipFuzzRouterFactory
import io.libp2p.pubsub.MockRouter
import io.libp2p.pubsub.PubsubRouterTest
import io.libp2p.pubsub.TestRouter
import io.libp2p.pubsub.gossip.builders.GossipPeerScoreParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.gossip.builders.GossipScoreParamsBuilder
import io.libp2p.tools.TestLogAppender
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.TimeUnit

class GossipPubsubRouterTest : PubsubRouterTest(
    createGossipFuzzRouterFactory {
        GossipRouterBuilder(params = GossipParams(3, 3, 100, floodPublish = false))
    }
) {

    @Test
    override fun TenNeighborsTopology() {
        for (d in 3..6) {
            for (seed in 0..10) {
                print("D=$d, seed=$seed  ")
                super.doTenNeighborsTopology(
                    seed,
                    createGossipFuzzRouterFactory {
                        // small backoff timeout for faster meshes settling down
                        GossipRouterBuilder(params = GossipParams(d, d, d, DLazy = 100, pruneBackoff = 1.seconds))
                    }
                )
            }
        }
    }

    @Test
    fun IHaveIWant() {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()

        val otherCount = 5
        for (i in 1..otherCount) {
            val r = { GossipRouterBuilder(params = GossipParams(1, 0)) }
            val routerEnd = fuzz.createTestGossipRouter(r)
            allRouters += routerEnd
        }

        // make routerCenter heartbeat trigger last to drop extra peers from the mesh
        // this is to test ihave/iwant
        fuzz.timeController.addTime(Duration.ofMillis(1))

        val r = { GossipRouterBuilder(params = GossipParams(3, 3, 3, DOut = 0, DLazy = 1000, floodPublish = false)) }
        val routerCenter = fuzz.createTestGossipRouter(r)
        allRouters.add(0, routerCenter)

        for (i in 1..otherCount) {
            allRouters[i].connectSemiDuplex(routerCenter, pubsubLogs = LogLevel.ERROR)
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        val receiveRouters = allRouters - routerCenter

        // heartbeat for all
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        routerCenter.router.publish(msg1)

        Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

        val msgCount1 = receiveRouters.sumOf { it.inboundMessages.size }
        println("Messages received on first turn: $msgCount1")

        // The message shouldn't be broadcasted to all peers (mesh size is limited to 3)
        Assertions.assertNotEquals(receiveRouters.size, msgCount1)
        receiveRouters.forEach { it.inboundMessages.clear() }

        // heartbeat where ihave/iwant should be used to deliver to all peers
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        val msgCount2 = receiveRouters.sumOf { it.inboundMessages.size }
        println("Messages received on second turn: $msgCount2")

        // now all peers should receive the message
        Assertions.assertEquals(receiveRouters.size, msgCount1 + msgCount2)
        receiveRouters.forEach { it.inboundMessages.clear() }

        // test the history roll up
        fuzz.timeController.addTime(Duration.ofSeconds(100))

        val msg2 = newMessage("topic1", 1L, "Hello".toByteArray())
        routerCenter.router.publish(msg2)

        // all routers should receive after 100 sec
        fuzz.timeController.addTime(Duration.ofSeconds(100))

        val msgCount3 = receiveRouters.sumOf { it.inboundMessages.size }
        println("Messages received on third turn: $msgCount3")

        // now all peers should receive the message
        Assertions.assertEquals(receiveRouters.size, msgCount3)
        receiveRouters.forEach { it.inboundMessages.clear() }
    }

    @Test
    fun testOneWayConnect() {
        // when remote gossip makes connection and immediately send IHAVE
        // the situation when we fail to send IWANT (as not outbound stream yet)
        // shouldn't be treated as internal error and no WARN logs should be printed
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createMockRouter()
        val router2 = fuzz.createTestRouter(routerFactory)
        val mockRouter = router1.router as MockRouter

        router2.router.subscribe("topic1")
        router1.connect(router2, LogLevel.INFO, LogLevel.INFO)

        TestLogAppender().install().use { testLogAppender ->
            val msg1 = Rpc.RPC.newBuilder()
                .setControl(
                    Rpc.ControlMessage.newBuilder().addIhave(
                        Rpc.ControlIHave.newBuilder().addMessageIDs("messageId".toByteArray().toProtobuf())
                    )
                ).build()

            mockRouter.sendToSingle(msg1)

            Assertions.assertFalse(testLogAppender.hasAnyWarns())
        }
    }

    @Test
    fun testOneWayConnectPublish() {
        // check that the published message is broadcasted successfully when one
        // of gossip peers is yet 'partially' connected
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createMockRouter()
        val router2 = fuzz.createTestRouter(routerFactory)
        val router3 = fuzz.createTestRouter(routerFactory)
        val mockRouter = router1.router as MockRouter

        router2.router.subscribe("topic1")
        router3.router.subscribe("topic1")
        router1.connect(router2, LogLevel.INFO, LogLevel.INFO)
        router2.connectSemiDuplex(router3, LogLevel.INFO, LogLevel.INFO)

        TestLogAppender().install().use { testLogAppender ->

            val msg1 = Rpc.RPC.newBuilder()
                .addSubscriptions(
                    Rpc.RPC.SubOpts.newBuilder()
                        .setTopicid("topic1")
                        .setSubscribe(true)
                )
                .setControl(
                    Rpc.ControlMessage.newBuilder().addGraft(
                        Rpc.ControlGraft.newBuilder().setTopicID("topic1")
                    )
                )
                .build()
            mockRouter.sendToSingle(msg1)

            fuzz.timeController.addTime(3.seconds)

            val msg2 = newMessage("topic1", 1L, "Hello".toByteArray())
            val future = router2.router.publish(msg2)
            Assertions.assertDoesNotThrow { future.get(1, TimeUnit.SECONDS) }
            Assertions.assertEquals(1, router3.inboundMessages.size)

            Assertions.assertFalse(testLogAppender.hasAnyWarns())
        }
    }

    @Test
    fun `test that no warn when trying to respond prune without outbound gossip stream`() {
        // when remote gossip makes connection and immediately send GRAFT
        // the situation when we fail to send PRUNE (as not outbound stream yet)
        // shouldn't be treated as internal error and no WARN logs should be printed
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createMockRouter()

        // when isDirect the gossip router should reply with PRUNE to GRAFT
        // this would reproduce the case
        val gossipScoreParams = GossipScoreParams(GossipPeerScoreParams(isDirect = { true }))

        val router2 = fuzz.createTestGossipRouter { GossipRouterBuilder(scoreParams = gossipScoreParams) }
        val mockRouter = router1.router as MockRouter

        router2.router.subscribe("topic1")
        router1.connect(router2, LogLevel.INFO, LogLevel.INFO)

        TestLogAppender().install().use { testLogAppender ->
            val msg1 = Rpc.RPC.newBuilder()
                .setControl(
                    Rpc.ControlMessage.newBuilder().addGraft(
                        Rpc.ControlGraft.newBuilder().setTopicID("topic1")
                    )
                ).build()

            mockRouter.sendToSingle(msg1)

            Assertions.assertFalse(testLogAppender.hasAnyWarns())
        }
    }

    @Test
    fun testIgnoreDoesntReduceScores() {
        // check that with Eth2 Gossip scoring params
        // a peers which IGNOREs all inbound messages doesn't get underscored

        val fuzz = DeterministicFuzz()
        val gossipScoreParams = GossipScoreParamsBuilder(Eth2DefaultScoreParams)
            .peerScoreParams(
                // disable colocation factor for simulation
                GossipPeerScoreParamsBuilder(Eth2DefaultPeerScoreParams).ipColocationFactorWeight(0.0).build()
            ).build()

        val allCount = 20
        val allRouters = (1..allCount).map {
            val r = { GossipRouterBuilder(params = Eth2DefaultGossipParams, scoreParams = gossipScoreParams) }
            fuzz.createTestRouter(createGossipFuzzRouterFactory(r))
        }

        val senderRouter = allRouters[0]
        val scoringRouter = allRouters[1]
        val ignoringRouter = allRouters[2]
        ignoringRouter.handlerValidationResult = RESULT_IGNORE
        val crowdRouters = allRouters.subList(3, allCount - 1)

        (crowdRouters + ignoringRouter).forEach {
            senderRouter.connectSemiDuplex(it)
            it.connectSemiDuplex(scoringRouter)
        }
        allRouters.forEach { it.router.subscribe(BlocksTopic) }

        fuzz.timeController.addTime(10.seconds)
        for (i in 0..100) {
            val msg = newMessage(BlocksTopic, i.toLong(), "Hello-$i".toByteArray())
            senderRouter.router.publish(msg)

            fuzz.timeController.addTime(20.seconds)
            assert(scoringRouter.inboundMessages.size > 0)
            scoringRouter.inboundMessages.clear()
        }

        val gossipRouter = scoringRouter.router as GossipRouter
        gossipRouter.peers.forEach {
            assertThat(gossipRouter.score.score(it.peerId)).isGreaterThanOrEqualTo(0.0)
        }
    }
}
