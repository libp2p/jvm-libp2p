@file:Suppress("ktlint:standard:class-naming")

package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.PubsubProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class GossipV1_2Tests : GossipTestsBase() {

    @Test
    fun selfSanityTest() {
        val test = TwoRoutersTest(protocol = PubsubProtocol.Gossip_V_1_2)

        test.mockRouter.subscribe("topic1")
        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    @Test
    fun iDontWantIsBroadcastToMeshPeers() {
        val test = ManyRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_2,
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5),
            mockRouterCount = 3
        )

        test.connectAll()

        test.gossipRouter.subscribe("topic1")
        test.mockRouters.forEach { it.subscribe("topic1") }

        // 2 heartbeats - the topic should be GRAFTed
        test.fuzz.timeController.addTime(2.seconds)

        val publisher = test.mockRouters[0]
        val gossipers = listOf(test.mockRouters[1], test.mockRouters[2])

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())

        publisher.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )

        test.fuzz.timeController.addTime(100.millis)

        val iDontWants = gossipers.flatMap { it.inboundMessages }
            .filter { it.hasControl() }
            .flatMap { it.control.idontwantList }

        // both mock peers should have received IDONTWANT from the GossipRouter
        assertTrue(iDontWants.size == 2)

        iDontWants.forEach { iDontWant ->
            assertThat(iDontWant.messageIDsList.map { it.toWBytes() }).containsExactly(msg.messageId)
        }
    }
}
