@file:Suppress("ktlint:standard:class-naming")

package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.PubsubProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class GossipV1_3Tests : GossipTestsBase() {

    @Test
    fun selfSanityTest() {
        val test = TwoRoutersTest(protocol = PubsubProtocol.Gossip_V_1_3)

        test.mockRouter.subscribe("topic1")
        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    @Test
    fun testBackoffTimeoutOnV1_3() {
        // Regression test: v1.3 must honor PRUNE backoff (inherited from v1.1).
        // Previously `supportsBackoffAndPX()` only returned true for v1.1/v1.2,
        // causing v1.3 routers to ignore the Backoff field and immediately re-GRAFT.
        val test = TwoRoutersTest(protocol = PubsubProtocol.Gossip_V_1_3)

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        // 2 heartbeats - the topic should be GRAFTed
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouter.waitForMessage { it.hasControl() && it.control.graftCount > 0 }
        test.mockRouter.inboundMessages.clear()

        val pruneMsg = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addPrune(
                Rpc.ControlPrune.newBuilder()
                    .setTopicID("topic1")
                    .setBackoff(30)
            )
        ).build()
        test.mockRouter.sendToSingle(pruneMsg)

        // No GRAFT should be sent during the backoff window
        test.fuzz.timeController.addTime(15.seconds)
        assertEquals(
            0,
            test.mockRouter.inboundMessages
                .count { it.hasControl() && it.control.graftCount > 0 }
        )
        test.mockRouter.inboundMessages.clear()

        // Expecting GRAFT after backoff expires
        test.fuzz.timeController.addTime(20.seconds)
        test.mockRouter.waitForMessage {
            it.hasControl() &&
                it.control.graftCount > 0 && it.control.getGraft(0).topicID == "topic1"
        }
    }
}
