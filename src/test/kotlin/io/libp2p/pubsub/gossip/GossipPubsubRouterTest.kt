package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.MockRouter
import io.libp2p.pubsub.PubsubRouterTest
import io.libp2p.pubsub.TestRouter
import io.libp2p.tools.TestLogAppender
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.TimeUnit

class GossipPubsubRouterTest : PubsubRouterTest({
    GossipRouter(
        GossipParams(3, 3, 100, floodPublish = false)
    )
}) {

    @Test
    override fun TenNeighborsTopology() {
        for (d in 3..6) {
            for (seed in 0..10) {
                print("D=$d, seed=$seed  ")
                super.doTenNeighborsTopology(seed) {
                    GossipRouter(
                        // small backoff timeout for faster meshes settling down
                        GossipParams(d, d, d, DLazy = 100, pruneBackoff = 1.seconds)
                    )
                }
            }
        }
    }

    @Test
    fun IHaveIWant() {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()

        val otherCount = 5
        for (i in 1..otherCount) {
            val r = GossipRouter(GossipParams(1, 0))
            val routerEnd = fuzz.createTestRouter(r)
            allRouters += routerEnd
        }

        // make routerCenter heartbeat trigger last to drop extra peers from the mesh
        // this is to test ihave/iwant
        fuzz.timeController.addTime(Duration.ofMillis(1))

        val r = GossipRouter(GossipParams(3, 3, 3, DOut = 0, DLazy = 1000, floodPublish = false))
        val routerCenter = fuzz.createTestRouter(r)
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

        val msgCount1 = receiveRouters.sumBy { it.inboundMessages.size }
        println("Messages received on first turn: $msgCount1")

        // The message shouldn't be broadcasted to all peers (mesh size is limited to 3)
        Assertions.assertNotEquals(receiveRouters.size, msgCount1)
        receiveRouters.forEach { it.inboundMessages.clear() }

        // heartbeat where ihave/iwant should be used to deliver to all peers
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        val msgCount2 = receiveRouters.sumBy { it.inboundMessages.size }
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

        val msgCount3 = receiveRouters.sumBy { it.inboundMessages.size }
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

        val router1 = fuzz.createTestRouter(MockRouter())
        val router2 = fuzz.createTestRouter(router())
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

        val router1 = fuzz.createTestRouter(MockRouter())
        val router2 = fuzz.createTestRouter(router())
        val router3 = fuzz.createTestRouter(router())
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

        val router1 = fuzz.createTestRouter(MockRouter())

        // when isDirect the gossip router should reply with PRUNE to GRAFT
        // this would reproduce the case
        val gossipScoreParams = GossipScoreParams(GossipPeerScoreParams(isDirect = { true }))

        val router2 = fuzz.createTestRouter(GossipRouter(scoreParams = gossipScoreParams))
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

        router2.connect(router1, LogLevel.INFO, LogLevel.INFO)

        val msg1 = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addMessageIDs("messageId".toByteArray().toProtobuf())
                )
            ).build()

        mockRouter.sendToSingle(msg1)
    }
}
