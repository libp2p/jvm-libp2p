package io.libp2p.pubsub

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.pubsub.flood.FloodRouter
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.tools.TestChannel.TestConnection
import io.netty.handler.logging.LogLevel
import io.netty.util.ResourceLeakDetector
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class PubsubRouterTest {

    init {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    }

    fun newMessage(topic: String, seqNo: Long, data: ByteArray) =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(data.toProtobuf())
            .build()

    @Test
    fun test1_Fanout() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(GossipRouter())
        val router2 = fuzz.createTestRouter(GossipRouter())
        router2.router.subscribe("topic1")

        router1.connectSemiDuplex(router2, LogLevel.ERROR, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg) // .get()

        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())

        System.gc()
        Thread.sleep(500)
        System.gc()
        Thread.sleep(500)
        System.gc()
    }

    @Test
    fun test2() {
        scenario2 { FloodRouter() }
        scenario2 { GossipRouter() }
    }

    fun scenario2(routerFactory: () -> PubsubRouterDebug) {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(routerFactory())
        val router2 = fuzz.createTestRouter(routerFactory())
        val router3 = fuzz.createTestRouter(routerFactory())

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

    @Test
    fun test3() {
        scenario3_StarTopology { FloodRouter() }
        scenario3_StarTopology { GossipRouter().withDConstants(3, 3, 100) }
    }

    fun scenario3_StarTopology(routerFactory: () -> PubsubRouterDebug) {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()

        val routerCenter = fuzz.createTestRouter(routerFactory())
        allRouters += routerCenter
        for (i in 1..20) {
            val routerEnd = fuzz.createTestRouter(routerFactory())
            allRouters += routerEnd
            routerEnd.connectSemiDuplex(routerCenter)
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))

        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        routerCenter.router.publish(msg1)

        Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

        val receiveRouters = allRouters - routerCenter

        val msgCount = receiveRouters.sumBy { it.inboundMessages.size }
        println("Messages received: $msgCount")

        Assertions.assertEquals(receiveRouters.size, msgCount)
        receiveRouters.forEach { it.inboundMessages.clear() }
    }

    @Test
    fun test4() {
        println("WheelTopology  FloodRouter:")
        scenario3_WheelTopology { FloodRouter() }
        println("WheelTopology  GossipRouter:")
        scenario3_WheelTopology { GossipRouter() }
    }
    fun scenario3_WheelTopology(routerFactory: () -> PubsubRouterDebug) {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()
        val allConnections = mutableListOf<TestConnection>()

        val routerCenter = fuzz.createTestRouter(routerFactory())
        allRouters += routerCenter
        for (i in 1..20) {
            val routerEnd = fuzz.createTestRouter(routerFactory())
            allRouters += routerEnd
            allConnections += routerEnd.connectSemiDuplex(routerCenter)
        }
        for (i in 0..19) {
            allConnections += allRouters[i + 1].connectSemiDuplex(allRouters[(i + 1) % 20 + 1])
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))
        run {
            val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
            routerCenter.router.publish(msg1)

            Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

            val receiveRouters = allRouters - routerCenter
            val msgCount = receiveRouters.sumBy { it.inboundMessages.size }
            val wireMsgCount = allConnections.sumBy { it.getMessageCount().toInt() }

            println("Messages received: $msgCount, total wire count: $wireMsgCount")

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }

        run {
            val msg1 = newMessage("topic1", 1L, "Hello".toByteArray())
            routerCenter.router.publish(msg1)

            Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

            val receiveRouters = allRouters - routerCenter
            val msgCount = receiveRouters.sumBy { it.inboundMessages.size }
            val wireMsgCount = allConnections.sumBy { it.getMessageCount().toInt() }

            println("Messages received: $msgCount, total wire count: $wireMsgCount")

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }
    }

    @Test
    fun test5() {
        println("10NeighborsTopology  FloodRouter:")
        scenario4_10NeighborsTopology { FloodRouter() }
        println("10NeighborsTopology  GossipRouter:")
        for (d in 3..6) {
            for (seed in 0..10) {
                print("D=$d, seed=$seed  ")
                scenario4_10NeighborsTopology(seed) { GossipRouter().withDConstants(d, d, d) }
            }
        }
    }
    fun scenario4_10NeighborsTopology(randomSeed: Int = 0, routerFactory: () -> PubsubRouterDebug) {
        val fuzz = DeterministicFuzz().also {
            it.randomSeed = randomSeed.toLong()
        }

        val allRouters = mutableListOf<TestRouter>()
        val allConnections = mutableListOf<TestConnection>()

        val nodesCount = 21
        val neighboursCount = 10

        for (i in 0 until nodesCount) {
            val routerEnd = fuzz.createTestRouter(routerFactory())
            allRouters += routerEnd
        }
        for (i in 0 until nodesCount) {
            for (j in 1..neighboursCount / 2)
            allConnections += allRouters[i].connectSemiDuplex(allRouters[(i + j) % 21]/*, pubsubLogs = LogLevel.ERROR*/)
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // 2 heartbeats for all
        fuzz.timeController.addTime(Duration.ofSeconds(2))
        val firstCount: Int
        run {
            val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
            allRouters[0].router.publish(msg1)

            Assertions.assertTrue(allRouters[0].inboundMessages.isEmpty())

            val receiveRouters = allRouters - allRouters[0]
            val msgCount = receiveRouters.sumBy { it.inboundMessages.size }
            firstCount = allConnections.sumBy { it.getMessageCount().toInt() }

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }

        run {
            val msg1 = newMessage("topic1", 1L, "Hello".toByteArray())
            allRouters[0].router.publish(msg1)

            Assertions.assertTrue(allRouters[0].inboundMessages.isEmpty())

            val receiveRouters = allRouters - allRouters[0]
            val msgCount = receiveRouters.sumBy { it.inboundMessages.size }
            val wireMsgCount = allConnections.sumBy { it.getMessageCount().toInt() }

            println(" Messages received: $msgCount, wire count: warm up: $firstCount, regular: ${wireMsgCount - firstCount}")
//           val missingRouters = receiveRouters.filter { it.inboundMessages.isEmpty() }
//           println(" Routers missing: " + missingRouters.joinToString(", ") { it.name })

            Assertions.assertEquals(receiveRouters.size, msgCount)
            receiveRouters.forEach { it.inboundMessages.clear() }
        }

//        val handler2router: (P2PService.PeerHandler) -> TestRouter = {
//            val channel = it.streamHandler.stream.nettyChannel
//            val connection = allConnections.find { channel == it.ch1 || channel == it.ch2 }!!
//            val otherChannel = if (connection.ch1 == channel) connection.ch2 else connection.ch1
//            allRouters.find { (it.router as AbstractRouter).peers.any { it.streamHandler.stream.nettyChannel == otherChannel } }!!
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
    fun testIHaveIWant() {
        val fuzz = DeterministicFuzz()

        val allRouters = mutableListOf<TestRouter>()

        val otherCount = 5
        for (i in 1..otherCount) {
            val r = GossipRouter().withDConstants(1, 0)
            val routerEnd = fuzz.createTestRouter(r)
            (routerEnd.router as GossipRouter).heartbeat // init heartbeat with current time
            allRouters += routerEnd
        }

        // make routerCenter heartbeat trigger last to drop extra peers from the mesh
        // this is to test ihave/iwant
        fuzz.timeController.addTime(Duration.ofMillis(1))

        val r = GossipRouter().withDConstants(3, 3, 3, 1000)
        val routerCenter = fuzz.createTestRouter(r)
        allRouters.add(0, routerCenter)

        for (i in 1..otherCount) {
            allRouters[i].connectSemiDuplex(routerCenter, pubsubLogs = LogLevel.ERROR)
        }

        allRouters.forEach { it.router.subscribe("topic1") }

        // heartbeat for all
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        val msg1 = newMessage("topic1", 0L, "Hello".toByteArray())
        routerCenter.router.publish(msg1)

        Assertions.assertTrue(routerCenter.inboundMessages.isEmpty())

        val receiveRouters = allRouters - routerCenter

        val msgCount1 = receiveRouters.sumBy { it.inboundMessages.size }
        println("Messages received on first turn: $msgCount1")

        // The message shouldn't be broadcasted to all peers (mesh size is limited to 3)
        Assertions.assertNotEquals(receiveRouters.size, msgCount1)
        receiveRouters.forEach { it.inboundMessages.clear() }

        // heartbeat where ihave/iwant should be used to deliver to all peers
        fuzz.timeController.addTime(Duration.ofSeconds(1))

        val msgCount2 = receiveRouters.sumBy { it.inboundMessages.size }
        println("Messages received on second turn: $msgCount2")

        // no all peers should receive the message
        Assertions.assertEquals(receiveRouters.size, msgCount1 + msgCount2)
        receiveRouters.forEach { it.inboundMessages.clear() }
    }

    @Test
    fun test_PublishFuture() {
        val fuzz = DeterministicFuzz()

        val router1 = fuzz.createTestRouter(GossipRouter())

        val msg0 = newMessage("topic1", 0L, "Hello".toByteArray())
        val publishFut0 = router1.router.publish(msg0)
        Assertions.assertThrows(ExecutionException::class.java, { publishFut0.get() })

        val router2 = fuzz.createTestRouter(GossipRouter())
        router2.router.subscribe("topic1")

        router1.connectSemiDuplex(router2, LogLevel.ERROR, LogLevel.ERROR)

        val msg = newMessage("topic1", 1L, "Hello".toByteArray())
        val publishFut = router1.router.publish(msg)

        publishFut.get(5, TimeUnit.SECONDS)
        Assertions.assertEquals(msg, router2.inboundMessages.poll(5, TimeUnit.SECONDS))
        Assertions.assertTrue(router1.inboundMessages.isEmpty())
        Assertions.assertTrue(router2.inboundMessages.isEmpty())
    }
}
