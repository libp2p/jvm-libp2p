package io.libp2p.pubsub.gossip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GossipParamsTest {

    @Test
    fun `test gossip scores disabled`() {
        GossipScoreParams.builder()
            .build()
    }

    @Test
    fun `test valid scores thresholds`() {
        GossipScoreParams.builder()
            .gossipThreshold(-0.01)
            .publishThreshold(-0.01)
            .graylistThreshold(-0.02)
            .build()
    }

    @Test
    fun `test valid publish threshold`() {
        GossipScoreParams.builder()
            .publishThreshold(-0.01)
            .graylistThreshold(-0.02)
            .build()
    }

    @Test
    fun `test valid graylist threshold`() {
        GossipScoreParams.builder()
            .graylistThreshold(-0.01)
            .build()
    }

    @Test
    fun `test valid score thresholds are big`() {
        GossipScoreParams.builder()
            .gossipThreshold(-1.01)
            .publishThreshold(-1.01)
            .graylistThreshold(-1.02)
            .build()
    }

    @Test
    fun `test invalid gossip threshold should be negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .gossipThreshold(0.01)
                .build()
        }
        assertEquals("gossipThreshold should be <= 0", exception.message)
    }

    @Test
    fun `test invalid publish threshold is greater than gossip threshold`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .gossipThreshold(-0.02)
                .publishThreshold(-0.01)
                .build()
        }
        assertEquals("publishThreshold should be <= than gossipThreshold", exception.message)
    }

    @Test
    fun `test invalid graylist threshold is equal to publish threshold`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .publishThreshold(-0.01)
                .graylistThreshold(-0.01)
                .build()
        }
        assertEquals("graylistThreshold should be < publishThreshold", exception.message)
    }

    @Test
    fun `test invalid graylist threshold is positive`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .graylistThreshold(0.01)
                .build()
        }
        assertEquals("graylistThreshold should be < publishThreshold", exception.message)
    }

    @Test
    fun `test invalid graylist threshold is disabled when others are not`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .gossipThreshold(-0.01)
                .publishThreshold(-0.02)
                .build()
        }
        assertEquals("graylistThreshold should be < publishThreshold", exception.message)
    }

    @Test
    fun `test valid accept px threshold`() {
        GossipScoreParams.builder()
            .acceptPXThreshold(0.01)
            .build()
    }

    @Test
    fun `test invalid accept px threshold is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .acceptPXThreshold(-0.01)
                .build()
        }
        assertEquals("acceptPXThreshold should be >= 0", exception.message)
    }

    @Test
    fun `test valid opportunistic graft threshold`() {
        GossipScoreParams.builder()
            .opportunisticGraftThreshold(0.01)
            .build()
    }

    @Test
    fun `test invalid opportunistic graft threshold is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .opportunisticGraftThreshold(-0.01)
                .build()
        }
        assertEquals("opportunisticGraftThreshold should be >= 0", exception.message)
    }
}
