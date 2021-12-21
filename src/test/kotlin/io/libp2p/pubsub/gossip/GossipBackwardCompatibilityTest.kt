package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.PubsubProtocol
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class GossipBackwardCompatibilityTest: TwoGossipHostTestBase() {
    override val router1 = GossipRouter(protocol = PubsubProtocol.Gossip_V_1_0)
    override val router2 = GossipRouter(protocol = PubsubProtocol.Gossip_V_1_1)

    @Test
    fun testConnect_1_0_to_1_1() {
        connect()

        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router1.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router1.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router2.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router2.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
    }

    @Test
    fun testConnect_1_1_to_1_0() {
        connect()

        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router1.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router1.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router2.peers[0].getInboundHandler()!!.stream.getProtocol().get()
        )
        Assertions.assertEquals(
            PubsubProtocol.Gossip_V_1_0.announceStr,
            router2.peers[0].getOutboundHandler()!!.stream.getProtocol().get()
        )
    }
}
