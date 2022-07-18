package io.libp2p.pubsub

import io.libp2p.core.pubsub.*
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.tools.TestChannel.TestConnection
import io.netty.handler.logging.LogLevel
import io.netty.util.ResourceLeakDetector
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

typealias RouterCtor = () -> PubsubRouterDebug

abstract class PubsubRouterTest(val routerFactory: DeterministicFuzzRouterFactory) {
    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    fun newMessage(topic: String, seqNo: Long, data: ByteArray) =
        DefaultPubsubMessage(
            Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
                .setData(data.toProtobuf())
                .build()
        )

    @Test
    fun Fanout() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory)
        val router2 = fuzz.createTestRouter(routerFactory)
        router2.router.subscribe("topic1")

        router1.connectSemiDuplex(router2, LogLevel.ERROR, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg) // .get()

        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
    }

    @Test
    fun testDoubleConnect() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory)
        val router2 = fuzz.createTestRouter(routerFactory)
        router2.router.subscribe("topic1")

        router1.connectSemiDuplex(router2, LogLevel.ERROR, LogLevel.ERROR)
        router1.connectSemiDuplex(router2, LogLevel.ERROR, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg) // .get()

        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
    }

    @Test
    fun testUnsubscribe() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory)
        val router2 = fuzz.createTestRouter(routerFactory)
        val router3 = fuzz.createTestRouter(routerFactory)
        val router4 = fuzz.createTestRouter(routerFactory)
        router1.router.subscribe("topic1")
        router1.router.subscribe("topic2")
        router2.router.subscribe("topic1")
        router2.router.subscribe("topic2")
        router3.router.subscribe("topic1")
        router3.router.subscribe("topic2")
        router4.router.subscribe("topic1")
        router4.router.subscribe("topic2")

        router2.connectSemiDuplex(router1, pubsubLogs = LogLevel.ERROR)
        router2.connectSemiDuplex(router3, pubsubLogs = LogLevel.ERROR)
        router2.connectSemiDuplex(router4, pubsubLogs = LogLevel.ERROR)
        fuzz.timeController.addTime(2.seconds)

        val msg1 = newMessage("topic1", 0L, "Hello-1".toByteArray())
        val msg2 = newMessage("topic2", 1L, "Hello-2".toByteArray())
        router1.router.publish(msg1)
        router1.router.publish(msg2)

        Assertions.assertEquals(msg1, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg2, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router3.inboundMessages.isEmpty())
        Assertions.assertEquals(msg1, router4.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg2, router4.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router4.inboundMessages.isEmpty())

        router2.router.unsubscribe("topic1")
        fuzz.timeController.addTime(2.seconds)
        val router2R = router2.router
        if (router2R is GossipRouter) {
            Assertions.assertNull((router2R.mesh["topic1"]))
        }

        val msg3 = newMessage("topic1", 2L, "Hello-3".toByteArray())
        val msg4 = newMessage("topic2", 3L, "Hello-4".toByteArray())
        router1.router.publish(msg3)
        router1.router.publish(msg4)

        Assertions.assertEquals(msg4, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router3.inboundMessages.isEmpty())
        Assertions.assertEquals(msg4, router4.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router4.inboundMessages.isEmpty())
    }

    @Test
    fun scenario2() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory)
        val router2 = fuzz.createTestRouter(routerFactory)
        val router3 = fuzz.createTestRouter(routerFactory)

        val conn_1_2 = router1.connectSemiDuplex(router2, pubsubLogs = LogLevel.ERROR)
        val conn_2_3 = router2.connectSemiDuplex(router3, pubsubLogs = LogLevel.ERROR)

        listOf(router1, router2, router3).forEach { it.router.subscribe("topic1", "topic2", "topic3") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg1)

        Assertions.assertEquals(msg1, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg1, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        val msg2 = newMessage("topic2", 1L, "Hello".toByteArray())
        router2.router.publish(msg2)

        Assertions.assertEquals(msg2, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg2, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        val conn_3_1 = router3.connectSemiDuplex(router1, pubsubLogs = LogLevel.ERROR)

        val msg3 = newMessage("topic3", 2L, "Hello".toByteArray())
        router2.router.publish(msg3)

        Assertions.assertEquals(msg3, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertEquals(msg3, router3.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        conn_2_3.disconnect()
        conn_3_1.disconnect()

        val msg4 = newMessage("topic3", 3L, "Hello - 4".toByteArray())
        router2.router.publish(msg4)

        Assertions.assertEquals(msg4, router1.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
        Assertions.assertTrue(router3.inboundMessages.isEmpty())

        conn_1_2.disconnect()
    }

    // scenario3_StarTopology { Gossiprouter.withDConstants(3, 3, 100) }
    @Test
    fun StarTopology() {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()

        val routerCenter = fuzz.createTestRouter(routerFactory)
        allRouters += routerCenter
        for (i in 1..20) {
            val routerEnd = fuzz.createTestRouter(routerFactory)
            allRouters += routerEnd
            routerEnd.connectSemiDuplex(routerCenter, pubsubLogs = LogLevel.ERROR)
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        routerCenter.router.publish(msg1)

        // 5 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(5))

        Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

        val receiveRouters = allRouters - routerCenter

        val msgCount = receiveRouters.sumOf { it.inboundMessages.size }
        println("Messages received: $msgCount")

        Assertions.assertEquals(receiveRouters.size, msgCount)
        receiveRouters.forEach { it.inboundMessages.clear() }
    }

    @Test
    fun WheelTopology() {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()
        val allConnections = mutableListOf<TestConnection>()

        val routerCenter = fuzz.createTestRouter(routerFactory)
        allRouters += routerCenter
        for (i in 1..20) {
            val routerEnd = fuzz.createTestRouter(routerFactory)
            allRouters += routerEnd
            allConnections += routerEnd.connectSemiDuplex(routerCenter).connections
        }
        for (i in 0..19) {
            allConnections += allRouters[i + 1].connectSemiDuplex(allRouters[(i + 1) % 20 + 1]).connections
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))
        run {
            val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
            routerCenter.router.publish(msg1)

            Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

            val receiveRouters = allRouters - routerCenter
            val msgCount = receiveRouters.sumOf { it.inboundMessages.size }
            val wireMsgCount = allConnections.sumOf { it.getMessageCount().toInt() }

            println("Messages received: $msgCount, total wire count: $wireMsgCount")

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }

        run {
            val msg1 = newMessage("topic1", 1L, "Hello".toByteArray())
            routerCenter.router.publish(msg1)

            Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

            val receiveRouters = allRouters - routerCenter
            val msgCount = receiveRouters.sumOf { it.inboundMessages.size }
            val wireMsgCount = allConnections.sumOf { it.getMessageCount().toInt() }

            println("Messages received: $msgCount, total wire count: $wireMsgCount")

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }
    }

    @Test
    open fun TenNeighborsTopology() {
        doTenNeighborsTopology()
    }

    fun doTenNeighborsTopology(randomSeed: Int = 0, routerFactory: DeterministicFuzzRouterFactory = this.routerFactory) {
        val fuzz = DeterministicFuzz().also {
            it.randomSeed = randomSeed.toLong()
        }

        val allRouters = mutableListOf<TestRouter>()
        val allConnections = mutableListOf<TestConnection>()

        val nodesCount = 21
        val neighboursCount = 10

        for (i in 0 until nodesCount) {
            val routerEnd = fuzz.createTestRouter(routerFactory)
            allRouters += routerEnd
        }
        for (i in 0 until nodesCount) {
            for (j in 1..neighboursCount / 2)
                allConnections += allRouters[i].connectSemiDuplex(allRouters[(i + j) % 21]/*, pubsubLogs = LogLevel.ERROR*/)
                    .connections
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // 10 heartbeats for all to settle down meshes evenly
        fuzz.timeController.addTime(Duration.ofSeconds(10))
        val firstCount: Int
        run {
            val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
            allRouters[0].router.publish(msg1)

            Assertions.assertTrue(allRouters[0].inboundMessages.isEmpty())

            // 5 heartbeats for all to give a chance for gossiping
            fuzz.timeController.addTime(Duration.ofSeconds(5))

            val receiveRouters = allRouters - allRouters[0]
            val msgCount = receiveRouters.sumOf { it.inboundMessages.size }
            firstCount = allConnections.sumOf { it.getMessageCount().toInt() }

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }

        run {
            val msg1 = newMessage("topic1", 1L, "Hello".toByteArray())
            allRouters[0].router.publish(msg1)

            Assertions.assertTrue(allRouters[0].inboundMessages.isEmpty())

            // 5 heartbeats for all to give a chance for gossiping
            fuzz.timeController.addTime(Duration.ofSeconds(5))

            val receiveRouters = allRouters - allRouters[0]
            val msgCount = receiveRouters.sumOf { it.inboundMessages.size }
            val wireMsgCount = allConnections.sumOf { it.getMessageCount().toInt() }

            println(" Messages received: $msgCount, wire count: warm up: $firstCount, regular: ${wireMsgCount - firstCount}")
//           val missingRouters = receiveRouters.filter { it.inboundMessages.isEmpty() }
//           println(" Routers missing: " + missingRouters.joinToString(", ") { it.name })

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }

//        val handler2router: (P2PService.PeerHandler) -> TestRouter = {
//            val channel = it.streamHandler.stream.nettyChannel()
//            val connection = allConnections.find { channel == it.ch1 || channel == it.ch2 }!!
//            val otherChannel = if (connection.ch1 == channel) connection.ch2 else connection.ch1
//            allRouters.find { (it.router as AbstractRouter).peers.any { it.streamHandler.stream.nettyChannel() == otherChannel } }!!
//        }
//        allRouters.forEach {tr ->
//            (tr.router as? GossipRouter)?.also {
//                val meshRouters = it.mesh.values.flatten().map(handler2router)
//                println("Mesh for ${tr.name}: " + meshRouters.joinToString(", ") { it.name })
//            }
//        }
//
//        allRouters.forEach {tr ->
//            (tr.router as? AbstractRouter)?.also {
//                val meshRouters = it.peers.map(handler2router)
//                println("Peers for ${tr.name}: " + meshRouters.joinToString(", ") { it.name })
//            }
//        }
    }

    @Test
    fun PublishFuture() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory)

        val msg0 = newMessage("topic1", 0L, "Hello".toByteArray())
        val publishFut0 = router1.router.publish(msg0)
        Assertions.assertThrows(ExecutionException::class.java, { publishFut0.get() })

        val router2 = fuzz.createTestRouter(routerFactory)
        router2.router.subscribe("topic1")

        router1.connectSemiDuplex(router2, LogLevel.ERROR, LogLevel.ERROR)

        val msg = newMessage("topic1", 1L, "Hello".toByteArray())
        val publishFut = router1.router.publish(msg)

        publishFut.get(5, TimeUnit.SECONDS)
        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
    }

    @Test
    fun validateTest() {
        val fuzz = DeterministicFuzz()

        val routers = List(3) { fuzz.createTestRouter(routerFactory) }

        routers[0].connectSemiDuplex(routers[1], pubsubLogs = LogLevel.ERROR)
        routers[1].connectSemiDuplex(routers[2], pubsubLogs = LogLevel.ERROR)

        val apis = routers.map { createPubsubApi(it.router) }
        class RecordingSubscriber : Subscriber {
            var count = 0
            override fun accept(t: MessageApi) {
                count++
            }
        }

        val topics = List(4) { Topic("topic$it") }

        val subs2 = topics
            .map { it to RecordingSubscriber() }
            .map { apis[2].subscribe(it.second, it.first); it.second }

        val scheduler = fuzz.createControlledExecutor()
        val delayed = { result: ValidationResult, delayMs: Long ->
            CompletableFuture<ValidationResult>().also {
                scheduler.schedule({ it.complete(result) }, delayMs, TimeUnit.MILLISECONDS)
            }
        }
        apis[1].subscribe(Validator { RESULT_VALID }, topics[0])
        apis[1].subscribe(Validator { RESULT_INVALID }, topics[1])
        apis[1].subscribe(Validator { delayed(ValidationResult.Valid, 500) }, topics[2])
        apis[1].subscribe(Validator { delayed(ValidationResult.Invalid, 500) }, topics[3])

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val publisher = apis[0].createPublisher(routers[0].keyPair.first)
        val msg = { "Hello".toByteArray().toByteBuf() }
        topics.forEach { publisher.publish(msg(), it) }

        Assertions.assertEquals(1, subs2[0].count)
        Assertions.assertEquals(0, subs2[1].count)
        Assertions.assertEquals(0, subs2[2].count)
        Assertions.assertEquals(0, subs2[3].count)

        fuzz.timeController.addTime(Duration.ofMillis(200))
        topics.forEach { publisher.publish(msg(), it) }

        Assertions.assertEquals(2, subs2[0].count)
        Assertions.assertEquals(0, subs2[1].count)
        Assertions.assertEquals(0, subs2[2].count)
        Assertions.assertEquals(0, subs2[3].count)

        // delayed validators should complete
        fuzz.timeController.addTime(Duration.ofMillis(400))

        Assertions.assertEquals(2, subs2[0].count)
        Assertions.assertEquals(0, subs2[1].count)
        Assertions.assertEquals(1, subs2[2].count)
        Assertions.assertEquals(0, subs2[3].count)

        fuzz.timeController.addTime(Duration.ofMillis(500))

        Assertions.assertEquals(2, subs2[0].count)
        Assertions.assertEquals(0, subs2[1].count)
        Assertions.assertEquals(2, subs2[2].count)
        Assertions.assertEquals(0, subs2[3].count)
    }
}
