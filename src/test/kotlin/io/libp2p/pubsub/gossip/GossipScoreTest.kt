package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.etc.util.P2PService
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GossipScoreTest {

    @Test
    fun `test misbehavior score threshold`() {
        val peerId = PeerId.random()
        val peerHandler = mockk<P2PService.PeerHandler>()
        every { peerHandler.peerId } returns peerId
        every { peerHandler.getIP() } returns "127.0.0.1"

        val peerScoreParams = GossipPeerScoreParams(
            decayInterval = 1.seconds,
            behaviourPenaltyWeight = -1.0,
            behaviourPenaltyDecay = 0.9,
            behaviourPenaltyThreshold = 5.0
        )
        val scoreParams = GossipScoreParams(peerScoreParams)
        val timeController = TimeControllerImpl()
        val executor = ControlledExecutorServiceImpl(timeController)

        val score = GossipScore(scoreParams, executor, { timeController.time })

        assertEquals(0.0, score.score(peerHandler))

        // not hit threshold yet
        score.notifyRouterMisbehavior(peerHandler, 5)
        assertEquals(0.0, score.score(peerHandler))

        // behaviourPenaltyThreshold reached
        score.notifyRouterMisbehavior(peerHandler, 1)
        assertTrue(score.score(peerHandler) < 0)

        // quadratic penalty
        score.notifyRouterMisbehavior(peerHandler, 10)
        assertTrue(score.score(peerHandler) < -50)

        // negative behaviour should not be forgotten so fast
        timeController.addTime(10.seconds)
        assertTrue(score.score(peerHandler) < 0)

        // time heals
        timeController.addTime(10.minutes)
        assertEquals(0.0, score.score(peerHandler))
    }
}
