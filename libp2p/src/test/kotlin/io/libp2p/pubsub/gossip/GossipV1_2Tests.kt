@file:Suppress("ktlint:standard:class-naming")

package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.millis
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toProtobuf
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
        val test = startSingleTopicNetwork(
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5),
            mockRouterCount = 3
        )

        val publisher = test.mockRouters[0]
        val gossipers = listOf(test.mockRouters[1], test.mockRouters[2])

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())

        publisher.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )

        test.fuzz.timeController.addTime(100.millis)

        val iDontWants =
            gossipers.flatMap { it.inboundMessages }.filter { it.hasControl() }.flatMap { it.control.idontwantList }

        // both gossipers should have received IDONTWANT from the GossipRouter
        assertTrue(iDontWants.size == 2)

        iDontWants.forEach { iDontWant ->
            assertThat(iDontWant.messageIDsList.map { it.toWBytes() }).containsExactly(msg.messageId)
        }
    }

    @Test
    fun messageIsNotBroadcastIfPeerHasSentIDONTWANT() {
        val test = startSingleTopicNetwork(
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5),
            mockRouterCount = 2
        )

        val publisher = test.mockRouters[0]
        val iDontWantPeer = test.mockRouters[1]

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())

        // sending IDONTWANT
        iDontWantPeer.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIdontwant(
                    Rpc.ControlIDontWant.newBuilder().addMessageIDs(msg.messageId.toProtobuf())
                )
            ).build()
        )

        test.fuzz.timeController.addTime(100.millis)

        publisher.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )

        test.fuzz.timeController.addTime(100.millis)

        val receivedMessages = iDontWantPeer.inboundMessages.flatMap { it.publishList }

        // message shouldn't have been received
        assertThat(receivedMessages).isEmpty()
    }

    @Test
    fun iDontWantIsNotSentIfSizeIsLessThanTheMinimumConfigured() {
        val test = startSingleTopicNetwork(
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5),
            mockRouterCount = 3
        )

        val publisher = test.mockRouters[0]
        val gossipers = listOf(test.mockRouters[1], test.mockRouters[2])

        // 4 bytes and minimum is 5, so IDONTWANT shouldn't be sent
        val msg = newMessage("topic1", 0L, "Hell".toByteArray())

        publisher.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )

        test.fuzz.timeController.addTime(100.millis)

        val iDontWants =
            gossipers.flatMap { it.inboundMessages }.filter { it.hasControl() }.flatMap { it.control.idontwantList }

        assertThat(iDontWants).isEmpty()
    }

    @Test
    fun testIDontWantTTL() {
        val test = startSingleTopicNetwork(
            // set TTL to 700ms
            params = GossipParams(iDontWantMinMessageSizeThreshold = 5, iDontWantTTL = 700.millis),
            mockRouterCount = 2
        )

        val publisher = test.mockRouters[0]
        val iDontWantPeer = test.mockRouters[1]

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())

        // sending IDONTWANT
        iDontWantPeer.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIdontwant(
                    Rpc.ControlIDontWant.newBuilder().addMessageIDs(msg.messageId.toProtobuf())
                )
            ).build()
        )

        // 1 heartbeat - the IDONTWANT should have expired
        test.fuzz.timeController.addTime(1.seconds)

        publisher.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(msg.protobufMessage).build()
        )

        test.fuzz.timeController.addTime(100.millis)

        val receivedMessages = iDontWantPeer.inboundMessages.flatMap { it.publishList }

        // message shouldn't have been received
        assertThat(receivedMessages).containsExactly(msg.protobufMessage)
    }

    private fun startSingleTopicNetwork(params: GossipParams, mockRouterCount: Int): ManyRoutersTest {
        val test = ManyRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_2,
            params = params,
            mockRouterCount = mockRouterCount
        )

        test.connectAll()

        test.gossipRouter.subscribe("topic1")
        test.mockRouters.forEach { it.subscribe("topic1") }

        // 2 heartbeats - the topic should be GRAFTed
        test.fuzz.timeController.addTime(2.seconds)

        return test
    }
}
