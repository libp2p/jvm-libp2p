package io.libp2p.pubsub.gossip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GossipParamsTest {

    /* GossipParams */

    @Test
    fun `test default gossip params`() {
        GossipParams.builder()
            .build()
    }

    @Test
    fun `test valid gossip param all zero`() {
        GossipParams.builder()
            .D(0)
            .DOut(0)
            .DLow(0)
            .DHigh(0)
            .build()
    }

    @Test
    fun `test valid dout less than dlow bigger nums`() {
        GossipParams.builder()
            .D(2000)
            .DOut(1000)
            .DLow(2000)
            .DHigh(2000)
            .build()
    }

    @Test
    fun `test valid dout and dlow are zero`() {
        GossipParams.builder()
            .D(2)
            .DOut(0)
            .DLow(0)
            .DHigh(2)
            .build()
    }

    @Test
    fun `test invalid d is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .D(-1)
                .build()
        }
        assertEquals("D should be >= 0", exception.message)
    }

    @Test
    fun `test invalid dout is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .DOut(-1)
                .build()
        }
        assertEquals("DOut should be >= 0", exception.message)
    }

    @Test
    fun `test invalid dlow is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .DLow(-1)
                .build()
        }
        assertEquals("DLow should be >= 0", exception.message)
    }

    @Test
    fun `test invalid dhigh is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .DHigh(-1)
                .build()
        }
        assertEquals("DHigh should be >= 0", exception.message)
    }

    @Test
    fun `test invalid dout same as dlow`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .DOut(1)
                .DLow(1)
                .build()
        }
        assertEquals("DOut should be < DLow or both 0", exception.message)
    }

    @Test
    fun `test invalid dout more than dlow`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .DOut(2)
                .DLow(1)
                .build()
        }
        assertEquals("DOut should be < DLow or both 0", exception.message)
    }

    @Test
    fun `test invalid dout greater than d div 2`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .D(2)
                .DOut(2)
                .DLow(3)
                .DHigh(2)
                .build()
        }
        assertEquals("DOut should be <= D/2", exception.message)
    }

    @Test
    fun `test invalid dlow greater than d`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .D(2)
                .DOut(1)
                .DLow(3)
                .DHigh(2)
                .build()
        }
        assertEquals("DLow should be <= D", exception.message)
    }

    @Test
    fun `test invalid dhigh less than d`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .D(2)
                .DOut(1)
                .DLow(2)
                .DHigh(1)
                .build()
        }
        assertEquals("DHigh should be >= D", exception.message)
    }

    @Test
    fun `test invalid gossip factor less than zero`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .gossipFactor(-0.01)
                .build()
        }
        assertEquals("gossipFactor should be in range [0.0, 1.0]", exception.message)
    }

    @Test
    fun `test invalid gossip factor more than one`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipParams.builder()
                .gossipFactor(1.01)
                .build()
        }
        assertEquals("gossipFactor should be in range [0.0, 1.0]", exception.message)
    }

    /* GossipScoreParams */

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
        assertEquals("graylistThreshold should be < publishThreshold or both 0", exception.message)
    }

    @Test
    fun `test invalid graylist threshold is positive`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .graylistThreshold(0.01)
                .build()
        }
        assertEquals("graylistThreshold should be < publishThreshold or both 0", exception.message)
    }

    @Test
    fun `test invalid graylist threshold is disabled when others are not`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipScoreParams.builder()
                .gossipThreshold(-0.01)
                .publishThreshold(-0.02)
                .build()
        }
        assertEquals("graylistThreshold should be < publishThreshold or both 0", exception.message)
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

    /* GossipPeerScoreParams */

    @Test
    fun `test default gossip peer score params`() {
        GossipPeerScoreParams.builder()
            .build()
    }

    @Test
    fun `test valid topicScoreCap`() {
        GossipPeerScoreParams.builder()
            .topicScoreCap(0.01)
            .build()
    }

    @Test
    fun `test invalid topicScoreCap is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .topicScoreCap(-0.01)
                .build()
        }
        assertEquals("topicScoreCap should be >= 0", exception.message)
    }

    @Test
    fun `test valid appSpecificWeight`() {
        GossipPeerScoreParams.builder()
            .appSpecificWeight(0.01)
            .build()
    }

    @Test
    fun `test invalid appSpecificWeight is negative`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .appSpecificWeight(-0.01)
                .build()
        }
        assertEquals("appSpecificWeight should be >= 0", exception.message)
    }

    @Test
    fun `test valid ipColocationFactorWeight with ipColocationFactorThreshold`() {
        GossipPeerScoreParams.builder()
            .ipColocationFactorWeight(-0.01)
            .ipColocationFactorThreshold(1)
            .build()
    }

    @Test
    fun `test invalid ipColocationFactorWeight is positive`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .ipColocationFactorWeight(0.01)
                .ipColocationFactorThreshold(1)
                .build()
        }
        assertEquals("ipColocationFactorWeight should be <= 0", exception.message)
    }

    @Test
    fun `test invalid ipColocationFactorWeight no ipColocationFactorThreshold`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .ipColocationFactorWeight(-0.01)
                .build()
        }
        assertEquals("ipColocationFactorThreshold should be >= 1", exception.message)
    }

    @Test
    fun `test invalid ipColocationFactorWeight invalid ipColocationFactorThreshold`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .ipColocationFactorWeight(-0.01)
                .ipColocationFactorThreshold(0)
                .build()
        }
        assertEquals("ipColocationFactorThreshold should be >= 1", exception.message)
    }

    @Test
    fun `test valid behaviourPenaltyWeight`() {
        GossipPeerScoreParams.builder()
            .behaviourPenaltyWeight(-0.01)
            .build()
    }

    @Test
    fun `test invalid behaviourPenaltyWeight`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .behaviourPenaltyWeight(0.01)
                .build()
        }
        assertEquals("behaviourPenaltyWeight should be <= 0", exception.message)
    }

    @Test
    fun `test valid behaviourPenaltyDecay`() {
        GossipPeerScoreParams.builder()
            .behaviourPenaltyDecay(0.01)
            .build()
    }

    @Test
    fun `test valid behaviourPenaltyDecay with behaviourPenaltyWeight`() {
        GossipPeerScoreParams.builder()
            .behaviourPenaltyWeight(-0.01)
            .behaviourPenaltyDecay(0.01)
            .build()
    }

    @Test
    fun `test invalid behaviourPenaltyDecay`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .behaviourPenaltyDecay(1.01)
                .build()
        }
        assertEquals("behaviourPenaltyDecay should be in range (0.0, 1.0]", exception.message)
    }

    @Test
    fun `test invalid behaviourPenaltyDecay with behaviourPenaltyWeight`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .behaviourPenaltyWeight(-0.01)
                .behaviourPenaltyDecay(1.01)
                .build()
        }
        assertEquals("behaviourPenaltyDecay should be in range (0.0, 1.0]", exception.message)
    }

    @Test
    fun `test valid behaviourPenaltyThreshold`() {
        GossipPeerScoreParams.builder()
            .behaviourPenaltyThreshold(0.01)
            .build()
    }

    @Test
    fun `test invalid behaviourPenaltyThreshold`() {
        val exception = assertThrows<IllegalArgumentException> {
            GossipPeerScoreParams.builder()
                .behaviourPenaltyThreshold(-0.01)
                .build()
        }
        assertEquals("behaviourPenaltyThreshold should be >= 0", exception.message)
    }

    /* GossipTopicScoreParams */
}
