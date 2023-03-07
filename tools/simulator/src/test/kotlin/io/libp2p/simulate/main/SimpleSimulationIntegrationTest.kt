package io.libp2p.simulate.main

import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.util.smartRound
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SimpleSimulationIntegrationTest {

    /**
     * The test compares result statistics to golden values
     * The values are deterministic but may insignificantly change in case of
     * Gossip or simulator modifications.
     * Please update the golden values only in case if it was expected
     */
    @Test
    fun `test result stats against golden values`() {
        val simpleSim = SimpleSimulation(nodeCount = 100, nodePeerCount = 3)
        simpleSim.publishMessage()

        val messagesResult = simpleSim.simulation.gossipMessageCollector.gatherResult()

        assertThat(messagesResult.getTotalMessageCount()).isEqualTo(795)
        assertThat(messagesResult.getTotalTraffic()).isEqualTo(6783557)

        val deliveryStats = simpleSim.simulation.gatherPubDeliveryStats()
        val deliveryAggrStats = StatsFactory.DEFAULT.createStats(deliveryStats.deliveryDelays)

        assertThat(deliveryAggrStats.getCount()).isEqualTo(99)
        val stats = deliveryAggrStats.getDescriptiveStatistics()
        assertThat(stats.min).isEqualTo(59.0)
        assertThat(stats.getPercentile(50.0).smartRound()).isEqualTo(323.0)
        assertThat(stats.max).isEqualTo(521.0)
    }

    @Test
    fun `test result stats against golden values several publish`() {
        val simpleSim = SimpleSimulation(nodeCount = 100, nodePeerCount = 3)

        repeat(5) {
            simpleSim.publishMessage(it)
        }

        val messagesResult = simpleSim.simulation.gossipMessageCollector.gatherResult()

        assertThat(messagesResult.getTotalMessageCount()).isEqualTo(1591)
        assertThat(messagesResult.getTotalTraffic()).isEqualTo(33787857)

        val deliveryStats = simpleSim.simulation.gatherPubDeliveryStats()
        val deliveryAggrStats = StatsFactory.DEFAULT.createStats(deliveryStats.deliveryDelays)

        assertThat(deliveryAggrStats.getCount()).isEqualTo(495)
        val stats = deliveryAggrStats.getDescriptiveStatistics()
        assertThat(stats.min).isEqualTo(59.0)
        assertThat(stats.getPercentile(50.0).smartRound()).isEqualTo(323.0)
        assertThat(stats.max).isEqualTo(524.0)
    }
}
