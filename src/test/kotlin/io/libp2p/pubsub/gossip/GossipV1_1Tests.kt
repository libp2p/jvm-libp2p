package io.libp2p.pubsub.gossip

import com.google.common.util.concurrent.AtomicDouble
import io.libp2p.core.pubsub.RESULT_INVALID
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.times
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.MockRouter
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

class GossipV1_1Tests {

    private fun newMessage(topic: String, seqNo: Long, data: ByteArray) =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(data.toProtobuf())
            .build()

    class TwoRoutersTest(
        val coreParams: GossipParamsCore = GossipParamsCore(),
        val v1_1Params: GossipParamsV1_1 = GossipParamsV1_1(coreParams),
        val scoreParams: GossipScoreParams = GossipScoreParams(),
        val gossipRouter: GossipRouter = GossipRouter(v1_1Params, scoreParams),
        val mockRouter: MockRouter = MockRouter()
    ) {
        val fuzz = DeterministicFuzz()
        val router1 = fuzz.createTestRouter(gossipRouter)
        val router2 = fuzz.createTestRouter(mockRouter)

        val connection = router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
    }

    @Test
    fun selfSanityTest() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    @Test
    fun testBackoffTimeout() {
        val test = TwoRoutersTest()

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

        // No GRAFT should be sent
        test.fuzz.timeController.addTime(15.seconds)
        assertEquals(0, test.mockRouter.inboundMessages
            .count { it.hasControl() && it.control.graftCount > 0 })
        test.mockRouter.inboundMessages.clear()

        // backoff should affect only one topic
        test.mockRouter.subscribe("topic2")
        test.gossipRouter.subscribe("topic2")
        test.fuzz.timeController.addTime(1.seconds)
        test.mockRouter.waitForMessage {
            it.hasControl() &&
                    it.control.graftCount == 1 && it.control.getGraft(0).topicID == "topic2"
        }

        // Still no GRAFT should be sent
        test.fuzz.timeController.addTime(10.seconds)
        assertEquals(0, test.mockRouter.inboundMessages
            .count { it.hasControl() && it.control.graftCount > 0 })
        test.mockRouter.inboundMessages.clear()

        // Expecting GRAFT now
        test.fuzz.timeController.addTime(10.seconds)
        test.mockRouter.waitForMessage {
            it.hasControl() &&
                    it.control.graftCount > 0 && it.control.getGraft(0).topicID == "topic1"
        }
        test.mockRouter.inboundMessages.clear()
    }

    @Test
    fun testGraftFloodPenalty() {
        val test = TwoRoutersTest()

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

        val graftMsg = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID("topic1")
            )
        ).build()

        assertEquals(1, test.gossipRouter.score.peerScores.size)
        val peerScores = test.gossipRouter.score.peerScores.values.first()
        // no behavior penalty before flooding
        assertEquals(0.0, peerScores.behaviorPenalty)

        for (i in 0..10) {
            test.mockRouter.sendToSingle(graftMsg)
        }
        test.fuzz.timeController.addTime(1.seconds)
        // behavior penalty after flooding
        assertTrue(peerScores.behaviorPenalty > 0.0)

        // check the penalty persist after reconnect
        test.connection.disconnect()
        test.fuzz.timeController.addTime(1.seconds)
        assertTrue(test.gossipRouter.peers.isEmpty())
        test.fuzz.timeController.addTime(1.seconds)

        val connection = test.router1.connectSemiDuplex(test.router2)
        test.fuzz.timeController.addTime(1.seconds)

        assertEquals(1, test.gossipRouter.score.peerScores.size)
        val peerScores1 = test.gossipRouter.score.peerScores.values.first()
        assertTrue(peerScores1.behaviorPenalty > 0.0)

        // check the penalty is decayed with time
        val origPenalty = peerScores1.behaviorPenalty
        test.fuzz.timeController.addTime(test.gossipRouter.score.params.decayInterval * 2)
        assertTrue(peerScores1.behaviorPenalty < origPenalty)
    }

    @Test
    fun testNoGraftWithNegativeScore() {
        val appScore = AtomicDouble()
        val peerScoreParams = GossipPeerScoreParams(appSpecificScore = { appScore.get() })
        val scoreParams = GossipScoreParams(peerScoreParams = peerScoreParams)
        val test = TwoRoutersTest(scoreParams = scoreParams)

        appScore.set(-1.0)

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        // The peer with negative score  shouldn't be added to the mesh even when undersubscribed
        test.fuzz.timeController.addTime(2.seconds)
        assertEquals(0, test.mockRouter.inboundMessages
            .count { it.hasControl() && it.control.graftCount > 0 })
        test.mockRouter.inboundMessages.clear()

        // Underscored peer should be rejected from joining mesh
        val graftMsg = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID("topic1")
            )
        ).build()
        test.mockRouter.sendToSingle(graftMsg)
        test.mockRouter.waitForMessage { it.hasControl() && it.control.pruneCount > 0 }

        // now the peer should be greylisted - all messages should be ignored
        appScore.set(-100500.0)
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouter.inboundMessages.clear()

        test.mockRouter.sendToSingle(graftMsg)
        test.fuzz.timeController.addTime(2.seconds)

        assertEquals(0, test.mockRouter.inboundMessages.size)
    }

    @Test
    fun testAppValidatorScore() {
        val test = TwoRoutersTest()
        val validator = AtomicReference<CompletableFuture<ValidationResult>>(RESULT_VALID)
        test.gossipRouter.initHandler { validator.get() }

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        test.fuzz.timeController.addTime(2.seconds)
        val peerScores1 = test.gossipRouter.score.peerScores.values.first()

        val msg1 = Rpc.RPC.newBuilder().addPublish(newMessage("topic1", 0L, "Hello-1".toByteArray())).build()
        test.mockRouter.sendToSingle(msg1)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages1 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0
        assertEquals(0.0, invalidMessages1)

        // message is invalid
        validator.set(RESULT_INVALID)
        val msg2 = Rpc.RPC.newBuilder().addPublish(newMessage("topic1", 1L, "Hello-2".toByteArray())).build()
        test.mockRouter.sendToSingle(msg2)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages2 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0
        assertTrue(invalidMessages2 > 0.0)

        // delayed validation
        val valFut = CompletableFuture<ValidationResult>()
        validator.set(valFut)
        val msg3 = Rpc.RPC.newBuilder().addPublish(newMessage("topic1", 2L, "Hello-3".toByteArray())).build()
        test.mockRouter.sendToSingle(msg3)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages3 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0

        assertEquals(invalidMessages2, invalidMessages3)

        valFut.complete(ValidationResult.Invalid)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages4 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0

        assertTrue(invalidMessages4 > invalidMessages3)

        // check invalid message counter is decayed
        test.fuzz.timeController.addTime(test.gossipRouter.score.params.decayInterval * 2)
        val invalidMessages5 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0
        assertTrue(invalidMessages5 < invalidMessages4)
    }

    @Test
    fun testGraftForUnknownTopicsAreIgnored() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        test.fuzz.timeController.addTime(2.seconds)

        test.mockRouter.waitForMessage {
            it.hasControl() &&
                    it.control.graftCount > 0 && it.control.getGraft(0).topicID == "topic1"
        }
        test.mockRouter.inboundMessages.clear()

        val graftMsg = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID("unknown-topic")
            )
        ).build()
        test.mockRouter.sendToSingle(graftMsg)
        test.fuzz.timeController.addTime(2.seconds)

        assertEquals(0, test.mockRouter.inboundMessages
            .count { it.hasControl() && it.control.graftCount + it.control.pruneCount > 0 })
    }
}
