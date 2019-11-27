package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.PubsubRouterTest
import io.libp2p.pubsub.TestRouter
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.time.Duration

class GossipPubsubRouterTest : PubsubRouterTest({ GossipRouter().withDConstants(3, 3, 100) }) {
    @Test
    override fun TenNeighborsTopology() {
        for (d in 3..6) {
            for (seed in 0..10) {
                print("D=$d, seed=$seed  ")
                super.doTenNeighborsTopology(seed) { GossipRouter().withDConstants(d, d, d) }
            }
        }
    }

    @Test
    fun IHaveIWant() {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()

        val otherCount = 5
        for (i in 1..otherCount) {
            val r = GossipRouter().withDConstants(1, 0)
            val routerEnd = fuzz.createTestRouter(r)
            (routerEnd.router as GossipRouter).heartbeat // init heartbeat with current time
            allRouters += routerEnd
        }

        // make routerCenter heartbeat trigger last to drop extra peers from the mesh
        // this is to test ihave/iwant
        fuzz.timeController.addTime(Duration.ofMillis(1))

        val r = GossipRouter().withDConstants(3, 3, 3, 1000)
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
}
