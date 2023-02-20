package io.libp2p.simulate

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.topology.AllToAllTopology
import io.libp2p.simulate.util.millis
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimulationTest {

    @Test
    fun test1() {
        val simConfig = GossipSimConfig(
            totalPeers = 3,
            topics = listOf(Topic(BlocksTopic)),
            topology = AllToAllTopology(),
            gossipValidationDelay = 0.millis
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            SimGossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
//                it.serialize = true
            }
        }

        val simPeerModifier = { _: Int, _: GossipSimPeer ->
//            peer.pubsubLogs = { true }
        }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor, simPeerModifier)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        val publishTime = simulation.forwardTime(1.seconds)

        simulation.publishMessage(0)
        simulation.forwardTime(1.seconds)

        val res = simulation.gatherMessageResults()

        assertThat(res).hasSize(1)

        val res0 = res.values.first()
        assertThat(res0).hasSize(2)

        val res0_0 = res0.first()
        assertThat(res0_0.receivedPeer).isEqualTo(1)
        assertThat(res0_0.receivedTime).isEqualTo(publishTime)

        val res0_1 = res0.last()
        assertThat(res0_1.receivedPeer).isEqualTo(2)
        assertThat(res0_1.receivedTime).isEqualTo(publishTime)

        println("Done")
    }
}
