package io.libp2p.pubsub.gossip

import com.google.common.util.concurrent.AtomicDouble
import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.RESULT_IGNORE
import io.libp2p.core.pubsub.RESULT_INVALID
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.Subscriber
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.pubsub.Validator
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.times
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.*
import io.libp2p.pubsub.DeterministicFuzz.Companion.createGossipFuzzRouterFactory
import io.libp2p.pubsub.DeterministicFuzz.Companion.createMockFuzzRouterFactory
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.handler.logging.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GossipV1_1Tests {

    private val GossipScore.testPeerScores get() = (this as DefaultGossipScore).peerScores

    private fun newProtoMessage(topic: Topic, seqNo: Long, data: ByteArray) =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(data.toProtobuf())
            .build()
    private fun newMessage(topic: Topic, seqNo: Long, data: ByteArray) =
        DefaultPubsubMessage(newProtoMessage(topic, seqNo, data))

    protected fun getMessageId(msg: Rpc.Message): MessageId = msg.from.toWBytes() + msg.seqno.toWBytes()

    class ManyRoutersTest(
        val mockRouterCount: Int = 10,
        val params: GossipParams = GossipParams(),
        val scoreParams: GossipScoreParams = GossipScoreParams(),
//            mockRouters: () -> List<MockRouter> = { (0 until mockRouterCount).map { MockRouter() } }
    ) {
        val fuzz = DeterministicFuzz()
        val gossipRouterBuilderFactory = { GossipRouterBuilder(params = params, scoreParams = scoreParams) }
        val router0 = fuzz.createTestRouter(createGossipFuzzRouterFactory(gossipRouterBuilderFactory))
        val routers = (0 until mockRouterCount).map { fuzz.createTestRouter(createMockFuzzRouterFactory()) }
        val connections = mutableListOf<SemiduplexConnection>()
        val gossipRouter = router0.router as GossipRouter
        val mockRouters = routers.map { it.router as MockRouter }

        fun connectAll() = connect(routers.indices)
        fun connect(routerIndexes: IntRange, outbound: Boolean = true): List<SemiduplexConnection> {
            val list =
                routers.slice(routerIndexes).map {
                    if (outbound) router0.connectSemiDuplex(it, null, LogLevel.ERROR)
                    else it.connectSemiDuplex(router0, null, LogLevel.ERROR)
                }
            connections += list
            return list
        }

        fun getMockRouter(peerId: PeerId) = mockRouters[routers.indexOfFirst { it.peerId == peerId }]
    }

    @Test
    fun selfSanityTest() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        test.gossipRouter.publish(msg)
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    class TwoRoutersTest(
        val coreParams: GossipParams = GossipParams(),
        val scoreParams: GossipScoreParams = GossipScoreParams(),
        mockRouterFactory: DeterministicFuzzRouterFactory = createMockFuzzRouterFactory()
    ) {
        val fuzz = DeterministicFuzz()
        val gossipRouterBuilderFactory = { GossipRouterBuilder(params = coreParams, scoreParams = scoreParams) }
        val router1 = fuzz.createTestRouter(createGossipFuzzRouterFactory(gossipRouterBuilderFactory))
        val router2 = fuzz.createTestRouter(mockRouterFactory)
        val gossipRouter = router1.router as GossipRouter
        val mockRouter = router2.router as MockRouter

        val connection = router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
    }

    @Test
    fun testSeenTTL() {
        val test = TwoRoutersTest(GossipParams(seenTTL = 1.minutes))

        test.mockRouter.subscribe("topic1")
        val msg1 = newMessage("topic1", 0L, "Hello-1".toByteArray())
        test.gossipRouter.publish(msg1)
        test.mockRouter.waitForMessage { it.publishCount == 1 }
        assertTrue(test.mockRouter.inboundMessages.isEmpty())

        test.fuzz.timeController.addTime(30.seconds)
        val msg2 = newMessage("topic1", 1L, "Hello-2".toByteArray())
        test.gossipRouter.publish(msg2)
        test.gossipRouter.publish(msg1) // should be in seen set
        test.mockRouter.waitForMessage { it.publishCount == 1 }
        assertTrue(test.mockRouter.inboundMessages.isEmpty())

        test.fuzz.timeController.addTime(31.seconds)
        val msg3 = newMessage("topic1", 2L, "Hello-3".toByteArray())
        test.gossipRouter.publish(msg3)
        test.gossipRouter.publish(msg1) // should be purged from seen and transmitted again
        test.mockRouter.waitForMessage { it.publishCount == 1 }
        test.mockRouter.waitForMessage { it.publishCount == 1 }
        assertTrue(test.mockRouter.inboundMessages.isEmpty())
    }

    @Test
    fun unknownTopicTest() {
        val test = TwoRoutersTest()

        val api = createPubsubApi(test.gossipRouter)
        val apiMessages = mutableListOf<MessageApi>()
        api.subscribe(Subscriber { apiMessages += it }, io.libp2p.core.pubsub.Topic("topic2"))

        val msg1 = Rpc.RPC.newBuilder()
            .addPublish(newProtoMessage("topic2", 0L, "Hello-1".toByteArray()))
            .addPublish(newProtoMessage("topic1", 1L, "Hello-2".toByteArray()))
            .addPublish(newProtoMessage("topic2", 2L, "Hello-3".toByteArray()))
            .build()
        test.mockRouter.sendToSingle(msg1)
        assertEquals(2, apiMessages.size)
        val messageBodies = apiMessages.map { it.data.toString(StandardCharsets.UTF_8) }
        assertTrue("Hello-1" in messageBodies)
        assertTrue("Hello-3" in messageBodies)
    }

    @Test
    fun testPenaltyForMalformedMessage() {
        class MalformedMockRouter(executor: ScheduledExecutorService) : MockRouter(executor) {
            var malform = false

            override fun initChannelWithHandler(streamHandler: StreamHandler, handler: ChannelHandler?) {
                streamHandler.stream.pushHandler(object : ChannelOutboundHandlerAdapter() {
                    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                        msg as ByteBuf
                        if (malform) {
                            val malformedPayload = Unpooled.wrappedBuffer(ByteArray(msg.readableBytes() - 5))
                            ctx.write(Unpooled.wrappedBuffer(msg.slice(0, 5), malformedPayload), promise)
                        } else {
                            ctx.write(msg, promise)
                        }
                    }
                })
                super.initChannelWithHandler(streamHandler, handler)
            }
        }
        val test = TwoRoutersTest(mockRouterFactory = { exec, _, _ -> MalformedMockRouter(exec) })
        val mockRouter = test.router2.router as MalformedMockRouter

        val api = createPubsubApi(test.gossipRouter)
        val apiMessages = mutableListOf<MessageApi>()
        api.subscribe(Subscriber { apiMessages += it }, io.libp2p.core.pubsub.Topic("topic1"))

        val msg1 = Rpc.RPC.newBuilder()
            .addPublish(newProtoMessage("topic1", 0L, "Hello-1".toByteArray()))
            .build()
        mockRouter.malform = true

        val peerScores = test.gossipRouter.score.testPeerScores.values.first()
        // no behavior penalty before flooding
        assertEquals(0.0, peerScores.behaviorPenalty)

        test.mockRouter.sendToSingle(msg1)

        assertTrue(peerScores.behaviorPenalty > 0)
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
        assertEquals(
            0,
            test.mockRouter.inboundMessages
                .count { it.hasControl() && it.control.graftCount > 0 }
        )
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
        assertEquals(
            0,
            test.mockRouter.inboundMessages
                .count { it.hasControl() && it.control.graftCount > 0 }
        )
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
    fun `test that acceptRequests whitelist is refreshed on timeout`() {
        val appScore = AtomicDouble()
        val peerScoreParams = GossipPeerScoreParams(
            appSpecificScore = { appScore.get() },
            appSpecificWeight = 1.0
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            graylistThreshold = -100.0
        )
        val test = TwoRoutersTest(scoreParams = scoreParams)

        // with this score the peer should be whitelisted for some period
        appScore.set(test.gossipRouter.acceptRequestsWhitelistThresholdScore.toDouble())

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        // 2 heartbeats - the topic should be GRAFTed
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouter.waitForMessage { it.hasControl() && it.control.graftCount > 0 }
        test.mockRouter.inboundMessages.clear()

        val msg1 = Rpc.RPC.newBuilder()
            .addPublish(newProtoMessage("topic1", 0L, "Hello-1".toByteArray()))
            .build()
        test.mockRouter.sendToSingle(msg1)
        // at this point peer is whitelisted for a period

        appScore.set(-101.0)

        val graftMsg = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID("topic1")
            )
        ).build()
        for (i in 0..2) {
            test.fuzz.timeController.addTime(50.millis)

            // even having the score below gralist threshold the peer should be answered because
            // it is still in acceptRequests whitelist
            test.mockRouter.sendToSingle(graftMsg)
            test.mockRouter.waitForMessage { it.hasControl() && it.control.pruneCount > 0 }
        }

        test.fuzz.timeController.addTime(test.gossipRouter.acceptRequestsWhitelistDuration)
        // at this point whitelist should be invalidated and score recalculated

        test.mockRouter.sendToSingle(graftMsg)
        // the last message should be ignored
        assertEquals(0, test.mockRouter.inboundMessages.size)
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

        assertEquals(1, test.gossipRouter.score.testPeerScores.size)
        val peerScores = test.gossipRouter.score.testPeerScores.values.first()
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

        test.router1.connectSemiDuplex(test.router2)
        test.fuzz.timeController.addTime(1.seconds)

        assertEquals(1, test.gossipRouter.score.testPeerScores.size)
        val peerScores1 = test.gossipRouter.score.testPeerScores.values.first()
        assertTrue(peerScores1.behaviorPenalty > 0.0)

        // check the penalty is decayed with time
        val origPenalty = peerScores1.behaviorPenalty
        test.fuzz.timeController.addTime(test.scoreParams.peerScoreParams.decayInterval * 2)
        assertTrue(peerScores1.behaviorPenalty < origPenalty)
    }

    @Test
    fun testNoGraftWithNegativeScore() {
        val appScore = AtomicDouble()
        val peerScoreParams = GossipPeerScoreParams(
            appSpecificScore = { appScore.get() },
            appSpecificWeight = 1.0
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            graylistThreshold = -100.0
        )
        val test = TwoRoutersTest(scoreParams = scoreParams)

        appScore.set(-1.0)

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        // The peer with negative score  shouldn't be added to the mesh even when undersubscribed
        test.fuzz.timeController.addTime(2.seconds)
        assertEquals(
            0,
            test.mockRouter.inboundMessages
                .count { it.hasControl() && it.control.graftCount > 0 }
        )
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
        val peerScores1 = test.gossipRouter.score.testPeerScores.values.first()

        val msg1 = Rpc.RPC.newBuilder().addPublish(newProtoMessage("topic1", 0L, "Hello-1".toByteArray())).build()
        test.mockRouter.sendToSingle(msg1)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages1 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0
        assertEquals(0.0, invalidMessages1)

        // message is invalid
        validator.set(RESULT_INVALID)
        val msg2 = Rpc.RPC.newBuilder().addPublish(newProtoMessage("topic1", 1L, "Hello-2".toByteArray())).build()
        test.mockRouter.sendToSingle(msg2)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages2 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0
        assertTrue(invalidMessages2 > 0.0)

        // delayed validation
        val valFut = CompletableFuture<ValidationResult>()
        validator.set(valFut)
        val msg3 = Rpc.RPC.newBuilder().addPublish(newProtoMessage("topic1", 2L, "Hello-3".toByteArray())).build()
        test.mockRouter.sendToSingle(msg3)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages3 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0

        assertEquals(invalidMessages2, invalidMessages3)

        valFut.complete(ValidationResult.Invalid)
        test.fuzz.timeController.addTime(1.seconds)

        val invalidMessages4 = peerScores1.topicScores["topic1"]?.invalidMessages ?: 0.0

        assertTrue(invalidMessages4 > invalidMessages3)

        // check invalid message counter is decayed
        test.fuzz.timeController.addTime(test.scoreParams.peerScoreParams.decayInterval * 2)
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

        assertEquals(
            0,
            test.mockRouter.inboundMessages
                .count { it.hasControl() && it.control.graftCount + it.control.pruneCount > 0 }
        )
    }

    @Test
    fun testLimitIHaveMessages() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        test.fuzz.timeController.addTime(2.seconds)

        test.mockRouter.inboundMessages.clear()

        for (i in 0..test.gossipRouter.params.maxIHaveMessages) {
            val msgId = "messageId-$i".toByteArray().toProtobuf()
            val ihaveMsg = Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder()
                        .setTopicID("topic1")
                        .addMessageIDs(msgId)
                )
            ).build()

            test.mockRouter.sendToSingle(ihaveMsg)

            if (i < test.gossipRouter.params.maxIHaveMessages) {
                test.mockRouter.waitForMessage {
                    it.hasControl() && it.control.iwantCount > 0 &&
                        it.control.getIwant(0).getMessageIDs(0) == msgId
                }
            }
        }
        test.fuzz.timeController.addTime(100.millis)
        assertEquals(
            0,
            test.mockRouter.inboundMessages
                .count { it.hasControl() && it.control.iwantCount > 0 }
        )
    }

    @Test
    fun testLimitIWantEntries() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        test.fuzz.timeController.addTime(2.seconds)

        test.mockRouter.inboundMessages.clear()
        val maxLen = test.gossipRouter.params.maxIHaveLength
        val almostMaxLen = maxLen - maxLen / 10

        val mids1 = (0 until almostMaxLen).map { "Id-$it".toByteArray().toProtobuf() }
        val ihaveMsg1 = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addIhave(
                Rpc.ControlIHave.newBuilder()
                    .setTopicID("topic1")
                    .addAllMessageIDs(mids1)
            )
        ).build()
        test.mockRouter.sendToSingle(ihaveMsg1)

        test.fuzz.timeController.addTime(100.millis)

        val mids2 = (almostMaxLen until almostMaxLen + maxLen).map { "Id-$it".toByteArray().toProtobuf() }
        val ihaveMsg2 = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addIhave(
                Rpc.ControlIHave.newBuilder()
                    .setTopicID("topic1")
                    .addAllMessageIDs(mids2)
            )
        ).build()
        test.mockRouter.sendToSingle(ihaveMsg2)

        test.fuzz.timeController.addTime(100.millis)
        val iWantCount = test.mockRouter.inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.iwantList }
            .flatMap { it.messageIDsList }
            .distinct()
            .count()

        assertEquals(maxLen, iWantCount)
    }

    @Test
    fun testNotFloodPublish() {
        val appScore = mutableMapOf<PeerId, Double>().withDefault { 0.0 }
        val coreParams = GossipParams(3, 3, 3, floodPublish = false)
        val peerScoreParams = GossipPeerScoreParams(appSpecificScore = { appScore.getValue(it) })
        val scoreParams = GossipScoreParams(peerScoreParams = peerScoreParams)
        val test = ManyRoutersTest(params = coreParams, scoreParams = scoreParams)
        test.connectAll()

        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }

        test.fuzz.timeController.addTime(2.seconds)

        val topicMesh = test.gossipRouter.mesh["topic1"]!!
        assertTrue(topicMesh.size > 0 && topicMesh.size < test.routers.size)

        test.gossipRouter.publish(newMessage("topic1", 0L, "Hello-0".toByteArray()))

        test.fuzz.timeController.addTime(50.millis)

        val publishedCount = test.mockRouters.flatMap { it.inboundMessages }.count { it.publishCount > 0 }

        // with floodPublish disabled the message should be delivered to mesh peers only
        assertEquals(topicMesh.size, publishedCount)
    }

    @Test
    fun testFloodPublish() {
        val appScore = mutableMapOf<PeerId, Double>().withDefault { 0.0 }
        val coreParams = GossipParams(3, 3, 3, floodPublish = true)
        val peerScoreParams = GossipPeerScoreParams(
            appSpecificScore = { appScore.getValue(it) },
            appSpecificWeight = 1.0
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            gossipThreshold = -5.0,
            publishThreshold = -10.0,
            graylistThreshold = -15.0
        )
        val test = ManyRoutersTest(params = coreParams, scoreParams = scoreParams)
        test.connectAll()

        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }

        test.fuzz.timeController.addTime(2.seconds)

        val topicMesh = test.gossipRouter.mesh["topic1"]!!.map { it.peerId }
        assertTrue(topicMesh.size > 0 && topicMesh.size < test.routers.size)

        test.gossipRouter.publish(newMessage("topic1", 0L, "Hello-0".toByteArray()))

        test.fuzz.timeController.addTime(50.millis)

        // the message should be broadcasted to all the peers due to flood publish
        test.mockRouters.forEach {
            it.waitForMessage { it.publishCount > 0 }
            it.inboundMessages.clear()
        }

        // the message originated from other peer should not be flood published
        val msg1 = Rpc.RPC.newBuilder().addPublish(newProtoMessage("topic1", 1L, "Hello-1".toByteArray())).build()
        test.mockRouters[0].sendToSingle(msg1)
        test.fuzz.timeController.addTime(50.millis)
        val publishedCount = test.mockRouters.flatMap { it.inboundMessages }.count { it.publishCount > 0 }
        assertTrue(publishedCount <= topicMesh.size)

        val scores1 = test.gossipRouter.peers.map { it.peerId to test.gossipRouter.score.score(it.peerId) }.toMap()

        // peers 0 and 1 should not receive flood publish
        appScore[test.routers[0].peerId] =
            ((scoreParams.publishThreshold - scores1[test.routers[0].peerId]!!) / peerScoreParams.appSpecificWeight) - 0.001
        appScore[test.routers[1].peerId] =
            ((scoreParams.publishThreshold - scores1[test.routers[1].peerId]!!) / peerScoreParams.appSpecificWeight) - 0.001
        // peers 2 and 3 should receive flood publish despite with score < 0
        appScore[test.routers[2].peerId] =
            ((scoreParams.publishThreshold - scores1[test.routers[2].peerId]!!) / peerScoreParams.appSpecificWeight) + 0.001
        appScore[test.routers[3].peerId] =
            ((scoreParams.publishThreshold - scores1[test.routers[3].peerId]!!) / peerScoreParams.appSpecificWeight) + 0.001

        println(appScore.keys)

        // check if scores are correctly calculated
        val scores2 = test.gossipRouter.peers.map { it.peerId to test.gossipRouter.score.score(it.peerId) }.toMap()
        assertTrue(scores2[test.routers[0].peerId]!! < scoreParams.publishThreshold)
        assertTrue(scores2[test.routers[1].peerId]!! < scoreParams.publishThreshold)
        assertTrue(scores2[test.routers[2].peerId]!! > scoreParams.publishThreshold)
        assertTrue(scores2[test.routers[2].peerId]!! < 0)
        assertTrue(scores2[test.routers[3].peerId]!! > scoreParams.publishThreshold)
        assertTrue(scores2[test.routers[3].peerId]!! < 0)

        test.gossipRouter.publish(newMessage("topic1", 2L, "Hello-2".toByteArray()))

        test.fuzz.timeController.addTime(50.millis)

        test.mockRouters.slice(2 until test.mockRouters.size)
            .forEach {
                it.waitForMessage { it.publishCount > 0 }
                it.inboundMessages.clear()
            }
        assertEquals(
            0,
            test.mockRouters[0].inboundMessages
                .count { it.publishCount > 0 }
        )
        assertEquals(
            0,
            test.mockRouters[1].inboundMessages
                .count { it.publishCount > 0 }
        )
    }

    @Test
    fun testAdaptiveGossip() {
        val appScore = mutableMapOf<PeerId, Double>().withDefault { 0.0 }
        val coreParams = GossipParams(
            3, 3, 3, DLazy = 3,
            floodPublish = false, gossipFactor = 0.5
        )
        val peerScoreParams = GossipPeerScoreParams(
            appSpecificScore = { appScore.getValue(it) },
            appSpecificWeight = 1.0
        )
        val scoreParams = GossipScoreParams(peerScoreParams = peerScoreParams)
        val test = ManyRoutersTest(
            mockRouterCount = 20,
            params = coreParams,
            scoreParams = scoreParams
        )

        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }

        test.connect(0..6)
        test.fuzz.timeController.addTime(2.seconds)

        test.gossipRouter.publish(newMessage("topic1", 0L, "Hello-0".toByteArray()))

        test.fuzz.timeController.addTime(test.gossipRouter.params.heartbeatInterval)

        val gossippedCount1 = test.mockRouters
            .flatMap { it.inboundMessages }
            .count { it.hasControl() && it.control.ihaveCount > 0 }

        // DLazy non meshed peers should be gossipped (DLazy < 3 * gossipFactor)
        assertEquals(3, gossippedCount1)
        test.mockRouters.forEach { it.inboundMessages.clear() }

        // connecting others
        test.connect(7..19)
        // should gossip again on the next heartbeat
        test.fuzz.timeController.addTime(test.gossipRouter.params.heartbeatInterval)

        val gossippedCount2 = test.mockRouters
            .flatMap { it.inboundMessages }
            .count { it.hasControl() && it.control.ihaveCount > 0 }

        // adaptive gossip dissemination: gossipFactor enters the game
        assertTrue(gossippedCount2 >= 7)
        assertTrue(gossippedCount2 < 17)
        test.mockRouters.forEach { it.inboundMessages.clear() }

        // shouldn't gossip to underscored peers
        test.routers.slice(0..9).map { it.peerId }.forEach { appScore[it] = -1000.0 }
        // should gossip again on the next heartbeat
        test.fuzz.timeController.addTime(test.gossipRouter.params.heartbeatInterval)

        val gossippedCount3 = test.mockRouters
            .flatMap { it.inboundMessages }
            .count { it.hasControl() && it.control.ihaveCount > 0 }
        val gossippedUnderscoreCount3 = test.mockRouters.slice(0..9)
            .flatMap { it.inboundMessages }
            .count { it.hasControl() && it.control.ihaveCount > 0 }
        assertTrue(gossippedCount3 > 0)
        assertEquals(0, gossippedUnderscoreCount3)
    }

    @Test
    fun testOutboundMeshQuotas1() {
        val appScore = mutableMapOf<PeerId, Double>().withDefault { 0.0 }
        val coreParams = GossipParams(3, 3, 3, DLazy = 3, DOut = 1, floodPublish = false)
        val peerScoreParams = GossipPeerScoreParams(appSpecificScore = { appScore.getValue(it) })
        val scoreParams = GossipScoreParams(peerScoreParams = peerScoreParams)
        val test = ManyRoutersTest(params = coreParams, scoreParams = scoreParams)

        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }

        test.connect(0..8, outbound = false)
        // mesh from inbound only should be formed
        test.fuzz.timeController.addTime(2.seconds)
        val meshedPeerIds = test.gossipRouter.mesh["topic1"]!!.map { it.peerId }
        assertEquals(3, meshedPeerIds.size)

        // inbound GRAFT should be rejected when oversubscribed
        val someNonMeshedPeer = test.getMockRouter(
            (test.routers.map { it.peerId } - meshedPeerIds).first()
        )
        val graftMsg = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().addGraft(
                Rpc.ControlGraft.newBuilder().setTopicID("topic1")
            )
        ).build()
        someNonMeshedPeer.sendToSingle(graftMsg)
        someNonMeshedPeer.waitForMessage { it.hasControl() && it.control.pruneCount > 0 }

        // making outbound connection
        val connection = test.connect(9..9, outbound = true)
        // outbound GRAFT should be accepted despite oversubscription
        test.mockRouters[9].sendToSingle(graftMsg)
        test.mockRouters[9].waitForMessage { it.hasControl() && it.control.graftCount > 0 }
        test.mockRouters[9].inboundMessages.clear()

        // gossip should actively add outbound peer to fill DOut gap
        connection[0].disconnect()
        test.fuzz.timeController.addTime(2.seconds)
        assertEquals(3, test.gossipRouter.mesh["topic1"]!!.size)
        test.connect(9..9, outbound = true)
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouters[9].waitForMessage { it.hasControl() && it.control.graftCount > 0 }
    }

    @Test
    fun testOpportunisticGraft() {
        val appScore = mutableMapOf<PeerId, Double>().withDefault { 0.0 }
        val coreParams = GossipParams(
            3, 3, 10, DLazy = 3, DOut = 1,
            opportunisticGraftPeers = 2, opportunisticGraftTicks = 60
        )
        val peerScoreParams = GossipPeerScoreParams(
            appSpecificScore = { appScore.getValue(it) },
            appSpecificWeight = 1.0
        )
        val scoreParams = GossipScoreParams(
            peerScoreParams = peerScoreParams,
            opportunisticGraftThreshold = 1000.0
        )
        val test = ManyRoutersTest(params = coreParams, scoreParams = scoreParams)

        test.connectAll()
        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }

        test.fuzz.timeController.addTime(2.seconds)
        val meshedPeerIds = test.gossipRouter.mesh["topic1"]!!.map { it.peerId }
        assertEquals(3, meshedPeerIds.size)
        val opportunisticGraftCandidates =
            (test.routers.map { it.peerId } - meshedPeerIds).take(3)
        opportunisticGraftCandidates.forEach { appScore[it] = 100500.0 }

        // opportunistic grafting should be applied only after 60 heartbeats
        test.fuzz.timeController.addTime(2.seconds)
        assertEquals(3, test.gossipRouter.mesh["topic1"]!!.size)

        // now [opportunisticGraftPeers] should be added to the mesh
        test.fuzz.timeController.addTime(60.seconds)
        val meshedPeerIds1 = test.gossipRouter.mesh["topic1"]!!.map { it.peerId }
        assertEquals(5, meshedPeerIds1.size)
        assertEquals(2, meshedPeerIds1.intersect(opportunisticGraftCandidates).size)
    }

    @Test
    fun testValidatorIgnoreResult() {
        val test = ManyRoutersTest(mockRouterCount = 2)
        val validator = AtomicReference<CompletableFuture<ValidationResult>>(RESULT_VALID)
        test.gossipRouter.initHandler { validator.get() }
        test.connectAll()
        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }
        test.fuzz.timeController.addTime(2.seconds)

        // when validator result is VALID the message should be propagated
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(newProtoMessage("topic1", 0L, "Hello-1".toByteArray())).build()
        )
        test.mockRouters[1].waitForMessage { it.publishCount > 0 }
        test.fuzz.timeController.addTime(1.seconds)

        // when validator result is IGNORE the message should not be propagated
        // and the score shouldn't be decreased
        validator.set(RESULT_IGNORE)
        test.mockRouters[0].sendToSingle(
            Rpc.RPC.newBuilder().addPublish(newProtoMessage("topic1", 0L, "Hello-1".toByteArray())).build()
        )
        test.fuzz.timeController.addTime(1.seconds)
        assertEquals(0, test.mockRouters[1].inboundMessages.count { it.publishCount > 0 })
        assertEquals(
            0.0,
            test.gossipRouter.score.testPeerScores[test.routers[0].peerId]!!.topicScores["topic1"]!!.invalidMessages
        )
    }

    @Test
    fun testSilenceDoesntReduceScores() {
        val test = ManyRoutersTest(mockRouterCount = 20)
        test.connectAll()
        test.gossipRouter.subscribe("topic1")
        test.routers.forEach { it.router.subscribe("topic1") }

        val idToPeerHandlers = test.gossipRouter.peers.map { it.peerId to it }.toMap()
        var curScores = idToPeerHandlers
            .mapValues { (_, handler) -> test.gossipRouter.score.score(handler.peerId) }
        assertEquals(0, curScores.values.count { it < 0 })
        for (i in 0..360) {
            assertEquals(20, curScores.size)
            test.fuzz.timeController.addTime(1.seconds)
            val newScores = idToPeerHandlers
                .mapValues { (_, handler) -> test.gossipRouter.score.score(handler.peerId) }
            for (id in curScores.keys) {
                assertTrue(newScores[id]!! >= curScores[id]!!)
            }
            curScores = newScores
        }
    }

    @Test
    fun testIWantTimeoutPenalty() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        // 2 heartbeats - the topic should be GRAFTed
        test.fuzz.timeController.addTime(2.seconds)

        val messages = (0..30).map {
            newMessage("topic1", it.toLong(), "Hello-$it".toByteArray())
        }
        val messageIds = messages.map { it.messageId.toProtobuf() }

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(messageIds.slice(0..9))
                )
            ).build()
        )

        test.mockRouter.waitForMessage { it.hasControl() && it.control.iwantCount > 0 }
        // 3 seconds is the default iwant response timeout
        test.fuzz.timeController.addTime(2.seconds)
        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().addAllPublish(messages.slice(0..9).map { it.protobufMessage }).build()
        )
        test.fuzz.timeController.addTime(10.seconds)

        // responded to IWANT in time - no penalties should be applied
        assertEquals(0.0, test.gossipRouter.score.testPeerScores[test.router2.peerId]!!.behaviorPenalty)

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(messageIds.slice(10..19))
                )
            ).build()
        )

        test.mockRouter.waitForMessage { it.hasControl() && it.control.iwantCount > 0 }
        // 3 seconds is the default iwant response timeout
        test.fuzz.timeController.addTime(10.seconds)
        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().addAllPublish(messages.slice(10..19).map { it.protobufMessage }).build()
        )
        test.fuzz.timeController.addTime(10.seconds)

        // messages were sent too late - penalty points should be applied
        val penalty1 = test.gossipRouter.score.testPeerScores[test.router2.peerId]!!.behaviorPenalty
        assertTrue(penalty1 > 0)

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs(messageIds.slice(20..29))
                )
            ).build()
        )
        test.fuzz.timeController.addTime(10.seconds)
        // all IWANT were ignored
        assertTrue(test.gossipRouter.score.testPeerScores[test.router2.peerId]!!.behaviorPenalty > penalty1)
    }

    @Test
    fun `IWANT timeout penalty shouldnt be applied if message received from other peer and not validated yet`() {
        val test = ManyRoutersTest(mockRouterCount = 2)
        test.connectAll()

        val api = createPubsubApi(test.gossipRouter)

        val validationResult = CompletableFuture<ValidationResult>()
        val receivedMessages = LinkedBlockingQueue<MessageApi>()
        val slowValidator = Validator { receivedMessages += it; validationResult }
        api.subscribe(slowValidator, io.libp2p.core.pubsub.Topic("topic1"))
        test.mockRouters.forEach { it.subscribe("topic1") }

        val gossiper = test.mockRouters[0]
        val gossiperRouter = test.routers[0]
        val publisher = test.mockRouters[1]

        val message = newMessage("topic1", 1L, "Hello-1".toByteArray())
        val messageId = message.messageId.toProtobuf()

        // 'gossiper' notifies it has the Message
        gossiper.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addMessageIDs(messageId)
                )
            ).build()
        )

        // 'publisher' sends the Message while 'gossiper' responding to IWANT request
        publisher.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(message.protobufMessage).build()
        )
        // the Message received but not validated yet
        assertNotNull(receivedMessages.poll(1, TimeUnit.SECONDS))

        // 'gossiper' responds to IWANT with the Message
        gossiper.sendToSingle(
            Rpc.RPC.newBuilder().addPublish(message.protobufMessage).build()
        )

        // the duplicate message shouldn't reach API
        assertNull(receivedMessages.poll(300, TimeUnit.MILLISECONDS))
        validationResult.complete(ValidationResult.Valid)

        // ... but should remove IWANT request timeout entry for 'gossiper' peer
        test.fuzz.timeController.addTime(10.seconds)
        // and the peer shouldn't be penalized
        assertTrue(test.gossipRouter.score.testPeerScores[gossiperRouter.peerId]!!.behaviorPenalty == 0.0)
    }

    @Test
    fun testGossipRetransmissionLimit() {
        val test = TwoRoutersTest()

        test.mockRouter.subscribe("topic1")
        test.gossipRouter.subscribe("topic1")

        test.fuzz.timeController.addTime(2.seconds)

        val message1 = newMessage("topic1", 0L, "Hello-0".toByteArray())
        test.gossipRouter.publish(message1)

        test.mockRouter.waitForMessage { it.publishCount > 0 }

        val message2 = newMessage("topic1", 1L, "Hello-1".toByteArray())
        test.gossipRouter.publish(message2)

        test.mockRouter.waitForMessage { it.publishCount > 0 }

        test.fuzz.timeController.addTime(1.seconds)
        test.mockRouter.inboundMessages.clear()

        for (i in 0..test.gossipRouter.params.gossipRetransmission) {
            test.mockRouter.sendToSingle(
                Rpc.RPC.newBuilder().setControl(
                    Rpc.ControlMessage.newBuilder().addIwant(
                        Rpc.ControlIWant.newBuilder().addMessageIDs(message1.messageId.toProtobuf())
                    )
                ).build()
            )
            if (i < test.gossipRouter.params.gossipRetransmission) {
                test.mockRouter.waitForMessage { it.publishCount > 0 }
            } else {
                // all IWANT requests above threshold should be ignored
                test.fuzz.timeController.addTime(1.seconds)
                assertEquals(0, test.mockRouter.inboundMessages.count { it.publishCount > 0 })
            }
            test.mockRouter.inboundMessages.clear()
        }

        // other message should be returned
        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIwant(
                    Rpc.ControlIWant.newBuilder().addMessageIDs(message2.messageId.toProtobuf())
                )
            ).build()
        )
        test.mockRouter.waitForMessage { it.publishCount > 0 }
    }

    @Test
    fun maxIHaveLengthTest() {
        val test = TwoRoutersTest(GossipParams(maxIHaveLength = 10))

        test.mockRouter.subscribe("topic1")

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs((0..4).map { "Id-$it".toByteArray().toProtobuf() })
                )
            ).build()
        )
        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs((5..14).map { "Id-$it".toByteArray().toProtobuf() })
                )
            ).build()
        )

        test.fuzz.timeController.addTime(100.millis)
        val iWandIds = test.mockRouter.inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.iwantList }
            .flatMap { it.messageIDsList }

        // Should ask only maxIHaveLength messages others should be ignored
        assertEquals(10, iWandIds.size)
        assertEquals(10, iWandIds.distinct().size)
        test.mockRouter.inboundMessages.clear()

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs((15..19).map { "Id-$it".toByteArray().toProtobuf() })
                )
            ).build()
        )
        // should still ignore IHAVE until next heartbeat
        assertEquals(0, test.mockRouter.inboundMessages.count { it.hasControl() && it.control.iwantCount > 0 })

        // heartbeat should reset inbound IHAVE counter
        test.fuzz.timeController.addTime(2.seconds)

        test.mockRouter.sendToSingle(
            Rpc.RPC.newBuilder().setControl(
                Rpc.ControlMessage.newBuilder().addIhave(
                    Rpc.ControlIHave.newBuilder().addAllMessageIDs((20..24).map { "Id-$it".toByteArray().toProtobuf() })
                )
            ).build()
        )
        test.fuzz.timeController.addTime(100.millis)
        val iWandIds1 = test.mockRouter.inboundMessages
            .filter { it.hasControl() }
            .flatMap { it.control.iwantList }
            .flatMap { it.messageIDsList }
        assertEquals(5, iWandIds1.size)
        assertEquals(5, iWandIds1.distinct().size)
    }
}
