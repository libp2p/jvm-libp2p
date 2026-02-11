@file:Suppress("ktlint:standard:class-naming")

package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.PubsubProtocol
import org.junit.jupiter.api.Test

class GossipV1_3Tests : GossipTestsBase() {

    @Test
    fun selfSanityTest() {
        val test = TwoRoutersTest(protocol = PubsubProtocol.Gossip_V_1_3)

        test.mockRouter.subscribe("topic1")
        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }
}
