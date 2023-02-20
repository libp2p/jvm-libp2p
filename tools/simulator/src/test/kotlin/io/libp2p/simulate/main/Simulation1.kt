package io.libp2p.simulate.main

import io.libp2p.core.pubsub.RESULT_INVALID
import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.toByteBuf
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.Topology
import io.libp2p.simulate.generateAndConnect
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.simulate.gossip.averagePubSubMsgSizeEstimator
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.WritableStats
import io.libp2p.simulate.stats.collect.GlobalNetworkStatsCollector
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.*
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.lang.Integer.max
import java.time.Duration
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.plus

class Simulation1 {

    val Topic = Topic("Topic-1")
    val MaxMissingPeers = 32

    data class GossipStats(
        val msgDelay: WritableStats,
        val someMissingPeers: List<GossipSimPeer> = emptyList()
    )

    data class SimConfig(
        val totalPeers: Int = 10000,
        val badPeers: Int = 0,

        val gossipD: Int = 6,
        val gossipDLow: Int = 3,
        val gossipDHigh: Int = 12,
        val gossipDLazy: Int = 6,
        val gossipAdvertise: Int = 3,
        val gossipHistory: Int = 5,
        val gossipHeartbeat: Duration = 1.seconds,
        val gossipHeartbeatAddDelay: RandomDistribution = RandomDistribution.const(0.0),
        val gossipValidationDelay: Duration = 0.millis,

        val avrgMessageSize: Int = 32 * 1024,
        val topology: Topology = RandomNPeers(10),
        val latency: RandomDistribution = RandomDistribution.const(1.0),
        val peersTimeShift: RandomDistribution = RandomDistribution.const(0.0)
    )

    data class SimOptions(
        val warmUpDelay: Duration = 5.seconds,
        val zeroHeartbeatsDelay: Duration = 500.millis, // 0 to skip this step
        val manyHeartbeatsDelay: Duration = 30.seconds,
        val generatedNetworksCount: Int = 1,
        val sentMessageCount: Int = 10,
        val startRandomSeed: Long = 0,
        val iterationThreadsCount: Int = 1,
        val parallelIterationsCount: Int = 1,
        val measureTCPFramesOverhead: Boolean = true
    ) {
        fun isZeroHeartbeatsEnabled(): Boolean = zeroHeartbeatsDelay.toMillis() > 0
    }

    data class SimResult(
        val packetCountPerMessage: WritableStats = StatsFactory.DEFAULT.createStats(),
        val trafficPerMessage: WritableStats = StatsFactory.DEFAULT.createStats(),
        val deliveredPart: WritableStats = StatsFactory.DEFAULT.createStats(),
        val deliverDelay: WritableStats = StatsFactory.DEFAULT.createStats()
    ) {
        fun getData() = mapOf(
            "msgCnt" to packetCountPerMessage.getStatisticalSummary().max,
            "traffic" to trafficPerMessage.getStatisticalSummary().max,
            "delivered%" to deliveredPart.getStatisticalSummary().mean,
            "delay(50%)" to deliverDelay.getDescriptiveStatistics().getPercentile(50.0),
            "delay(95%)" to deliverDelay.getDescriptiveStatistics().getPercentile(95.0),
            "delay(max)" to deliverDelay.getDescriptiveStatistics().max
        )
    }

    data class SimDetailedResult(
        val zeroHeartbeats: SimResult = SimResult(),
        val manyHeartbeats: SimResult = SimResult()
    ) {
        fun getData(includeZero: Boolean = true) =
            if (includeZero) {
                zeroHeartbeats.getData().setKeys { "0-$it" } +
                    manyHeartbeats.getData().setKeys { "N-$it" }
            } else {
                manyHeartbeats.getData()
            }
    }

    @Disabled
    @Test
    fun testResultStabilityAgainstNetworkSize() {
        val cfgs = sequence {
            for (totalPeers in arrayOf(1000/*, 5000, 10000, 20000, 30000*/))
                yield(
                    SimConfig(
                        totalPeers = totalPeers,
                        badPeers = (0.1 * totalPeers).toInt(),
                        topology = RandomNPeers(20),

                        gossipD = 6,
                        gossipDLow = 5,
                        gossipDHigh = 7,
                        gossipDLazy = 6
                    )
                )
        }
        val opt = SimOptions(
            generatedNetworksCount = 1,
            sentMessageCount = 3,
            startRandomSeed = 2
        )

        sim(cfgs, opt)
    }

    @Disabled
    @Test
    fun testBFT() {
        val totalPeers = 10000
        val cfgs = sequence {
            for (badPeers in arrayOf(0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.93, 0.95, 0.97)) {
                yield(
                    SimConfig(
                        totalPeers = totalPeers,
                        badPeers = (badPeers * totalPeers).toInt(),
                        topology = RandomNPeers(20),

                        gossipD = 6,
                        gossipDLow = 5,
                        gossipDHigh = 7,
                        gossipDLazy = 6
                    )
                )
            }
        }
        val opt = SimOptions(
            generatedNetworksCount = 10,
            sentMessageCount = 5,
            startRandomSeed = 0
        )

        sim(cfgs, opt)
    }

    @Disabled
    @Test
    fun testBFTOfPeerConnections() {
        val cfgs = sequence {
            for (peerConnections in arrayOf(6, 8, 10, 12, 15, 17, 20, 25, 30, 40, 50, 60, 80, 100)) {
                yield(
                    SimConfig(
                        totalPeers = 10000,
                        badPeers = 9000,
                        topology = RandomNPeers(peerConnections),

                        gossipD = 6,
                        gossipDLow = 5,
                        gossipDHigh = 7,
                        gossipDLazy = 100
                    )
                )
            }
        }
        val opt = SimOptions(
            generatedNetworksCount = 10,
            sentMessageCount = 5,
            startRandomSeed = 0
        )

        sim(cfgs, opt)
    }

    @Disabled
    @Test
    fun testSizeOptimization1() {
        val cfgs = sequence {
            for (gossipD in arrayOf(1, 2, 3, 4, 5, 6, 7))
                yield(
                    SimConfig(
                        totalPeers = 5000,
                        badPeers = 0,
                        topology = RandomNPeers(20),

                        gossipD = gossipD,
                        gossipDLow = max(1, gossipD - 1),
                        gossipDHigh = gossipD + 1,
                        gossipDLazy = 10,
                        gossipHeartbeatAddDelay = RandomDistribution.uniform(0.0, 1000.0)
                    )
                )
        }
        val opt = SimOptions(
            generatedNetworksCount = 10,
            sentMessageCount = 3,
            startRandomSeed = 3,
            parallelIterationsCount = 4
        )

        sim(cfgs, opt)
    }

    @Disabled
    @Test
    fun testSizeOptimizationDLazy() {
        val cfgs = sequence {
            for (avrgMessageSize in arrayOf(32 * 1024, 512))
                for (gossipDLazy in arrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20))
                    yield(
                        SimConfig(
                            totalPeers = 5000,
                            badPeers = 0,
                            topology = RandomNPeers(20),

                            gossipD = 6,
                            gossipDLow = 5,
                            gossipDHigh = 7,
                            gossipDLazy = gossipDLazy,
                            gossipHeartbeatAddDelay = RandomDistribution.uniform(0.0, 1000.0),

                            avrgMessageSize = avrgMessageSize,
                            latency = RandomDistribution.uniform(1.0, 50.0)
                        )
                    )
        }
        val opt = SimOptions(
            zeroHeartbeatsDelay = 0.millis,
            generatedNetworksCount = 10,
            sentMessageCount = 3,
            startRandomSeed = 3,
            parallelIterationsCount = 4
        )

        sim(cfgs, opt)
    }

    @Disabled
    @Test
    fun testHeartbeatPeriod() {
        val cfgs = sequence {
            for (gossipHeartbeat in arrayOf(1000, 500, 300, 100, 50, 30, 10))
                yield(
                    SimConfig(
                        totalPeers = 5000,
                        badPeers = 4500,
                        topology = RandomNPeers(20),

                        gossipD = 6,
                        gossipDLow = 3,
                        gossipDHigh = 12,
                        gossipDLazy = 10,
                        gossipHeartbeat = gossipHeartbeat.millis,
                        gossipHeartbeatAddDelay = RandomDistribution.uniform(0.0, 1000.0),
                        gossipHistory = 100, // increase history to serve low latency IWANT requests

                        latency = RandomDistribution.uniform(1.0, 50.0)
                    )
                )
        }
        val opt = SimOptions(
            warmUpDelay = 10.seconds,
            zeroHeartbeatsDelay = 0.millis,
            manyHeartbeatsDelay = 30.seconds,
            generatedNetworksCount = 10,
            sentMessageCount = 3,
            parallelIterationsCount = 4
        )

        sim(cfgs, opt)
    }

/*
    @Disabled
    @Test
    fun testImmediateGossip() {
        val cfgs = sequence {
            for (gossipDLazy in arrayOf(10, 15, 20, 25))
            for (badPeers in arrayOf(0.0, 0.90, 0.95, 0.97))
            for (immediateGossip in arrayOf(false, true))
                yield(
                    SimConfig(
                        totalPeers = 5000,
                        badPeers = (5000 * badPeers).toInt(),
                        topology = RandomNPeers(30),

                        gossipD = 6,
                        gossipDLow = 5,
                        gossipDHigh = 7,
                        gossipDLazy = gossipDLazy,
                        gossipAdvertise = 1,
                        gossipHeartbeatAddDelay = RandomDistribution.uniform(0.0, 1000.0),
                        gossipHistory = 100, // increase history to serve low latency IWANT requests

                        latency = RandomDistribution.uniform(1.0, 50.0)
                    )
                )
        }
        val opt = SimOptions(
            warmUpDelay = 10.seconds,
            zeroHeartbeatsDelay = 0.millis,
            manyHeartbeatsDelay = 30.seconds,
            generatedNetworksCount = 10,
            sentMessageCount = 3,
            parallelIterationsCount = 4
        )

        sim(cfgs, opt)
    }
*/

    fun sim(cfg: Sequence<SimConfig>, opt: SimOptions): List<SimDetailedResult> {
        val executorService = Executors.newFixedThreadPool(opt.parallelIterationsCount)
        val cfgList = cfg.toList()
        val resFut = cfgList
            .map { config ->
                executorService.submit(
                    Callable {
                        println("Starting sim: \n\t$config\n\t$opt")
                        val r = sim(config, opt)
                        println("Complete: $r")
                        r
                    }
                )
            }

        val res = resFut.map { it.get() }

        val variativeConfigProps = cfgList
            .map { it.propertiesAsMap() }
            .transpose().asSequence()
            .filter { it.value.distinct().size > 1 }
            .associate { it.key to it.value.map { it.toString() } }
            .transpose()

        println("Results: ")
        println("==============")

        val resData = res.map { it.getData(opt.isZeroHeartbeatsEnabled()) }
        val resDataFormatted = resData.map { it.mapValues { it.value.smartRound().toString() } }
        val totalData = (variativeConfigProps.transpose() + resDataFormatted.transpose()).transpose()

        val headers = totalData[0].keys.joinToString("\t")
        val data = totalData.joinToString("\n") { it.values.joinToString("\t") }

        val table = (headers + "\n" + data).formatTable(true)
        println(table)

        return res
    }

    fun sim(cfg: SimConfig, opt: SimOptions): SimDetailedResult {

        val ret = SimDetailedResult()
        for (n in 0 until opt.generatedNetworksCount) {
            val commonRnd = Random(opt.startRandomSeed + n)
            val peerTimeShift = cfg.peersTimeShift.newValue(commonRnd)
            val gossipHeartbeatAddDelay = cfg.gossipHeartbeatAddDelay.newValue(commonRnd)

            val timeController = TimeControllerImpl()
            println("Creating peers")

            val peerExecutors =
                if (opt.iterationThreadsCount > 1)
                    (0 until opt.iterationThreadsCount).map { Executors.newSingleThreadScheduledExecutor() }
                else
                    listOf(Executor { it.run() })

            val peers = (0 until cfg.totalPeers).map {
                GossipSimPeer(listOf(Topic), it.toString(), commonRnd).apply {
//                    val gossipParams = GossipParams(
//                        D = cfg.gossipD,
//                        DLow = cfg.gossipDLow,
//                        DHigh = cfg.gossipDHigh,
//                        DLazy = cfg.gossipDLazy,
//                        gossipSize = cfg.gossipAdvertise,
//                        gossipHistoryLength = cfg.gossipHistory,
//                        heartbeatInterval = cfg.gossipHeartbeat
//                    )
                    routerBuilder.apply {
                        additionalHeartbeatDelay = gossipHeartbeatAddDelay.next().toInt().millis
                        serializeMessagesToBytes = false
                        val timeShift = peerTimeShift.next().toLong()
                        currentTimeSuppluer = { timeController.time + timeShift }
                        random = commonRnd
                    }

                    val delegateExecutor = peerExecutors[it % peerExecutors.size]
                    simExecutor = ControlledExecutorServiceImpl(delegateExecutor, timeController)
                    msgSizeEstimator =
                        averagePubSubMsgSizeEstimator(cfg.avrgMessageSize, opt.measureTCPFramesOverhead).sizeEstimator
//                    val latencyRandomValue = cfg.latency.newValue(commonRnd)
                    validationDelay = cfg.gossipValidationDelay

                    start()
                }
            }
            println("Creating test peers")
            peers[(cfg.totalPeers - cfg.badPeers) until cfg.totalPeers]
                .forEach { it.validationResult = RESULT_INVALID }

            cfg.topology.random = commonRnd

            println("Connecting peers")
            val net = cfg.topology.generateAndConnect(peers)
            val networkStatsCollector = GlobalNetworkStatsCollector(net, GeneralSizeEstimator)

            data class NetworkStats(
                val msgCount: Long,
                val traffic: Long
            ) {
                operator fun minus(other: NetworkStats) =
                    NetworkStats(msgCount - other.msgCount, traffic - other.traffic)
                operator fun plus(other: NetworkStats) =
                    NetworkStats(msgCount + other.msgCount, traffic + other.traffic)
            }
            fun getNetStats() = NetworkStats(networkStatsCollector.msgCount, networkStatsCollector.msgsSize)

            println("Some warm up")
            timeController.addTime(opt.warmUpDelay)

            var lastNS = getNetStats()
            println("Initial stat: $lastNS")
            networkStatsCollector.msgSizeStats.reset()

            for (i in 0 until opt.sentMessageCount) {
                println("Sending message #$i...")

                val sentTime = timeController.time
                peers[i].apiPublisher.publish("Message-$i".toByteArray().toByteBuf(), Topic)

                val receivePeers = peers - peers[i]

                if (opt.isZeroHeartbeatsEnabled()) {
                    timeController.addTime(opt.zeroHeartbeatsDelay)
                    run {
                        val ns = getNetStats()
                        val gs = calcGossipStats(receivePeers, sentTime)
                        ret.zeroHeartbeats.packetCountPerMessage.addValue(ns.msgCount.toDouble() / peers.size)
                        ret.zeroHeartbeats.trafficPerMessage.addValue(ns.traffic.toDouble() / peers.size)
                        receivePeers.filter { it.lastMsg != null }
                            .map { it.lastMsgTime - sentTime }
                            .forEach { ret.zeroHeartbeats.deliverDelay.addValue(it) }
                        ret.zeroHeartbeats.deliveredPart.addValue(gs.msgDelay.getCount().toDouble() / receivePeers.size)
                        println("Zero heartbeats: $ns\t\t$gs")
                    }
                }

                timeController.addTime(opt.manyHeartbeatsDelay)

                val ns0: NetworkStats
                run {
                    val ns = getNetStats()
                    ns0 = ns
                    val gs = calcGossipStats(receivePeers, sentTime)
                    ret.manyHeartbeats.packetCountPerMessage.addValue(ns.msgCount.toDouble() / peers.size)
                    ret.manyHeartbeats.trafficPerMessage.addValue(ns.traffic.toDouble() / peers.size)
                    receivePeers.filter { it.lastMsg != null }
                        .map { it.lastMsgTime - sentTime }
                        .forEach { ret.manyHeartbeats.deliverDelay.addValue(it) }
                    ret.manyHeartbeats.deliveredPart.addValue(gs.msgDelay.getCount().toDouble() / receivePeers.size)
                    println("Many heartbeats: $ns\t\t$gs")
                }

                timeController.addTime(Duration.ofSeconds(10))
                val nsDiff = getNetStats() - ns0
                println("Empty time: $nsDiff")

                networkStatsCollector.msgSizeStats.reset()
                clearGossipStats(peers)
            }
        }
        return ret
    }

    private fun clearGossipStats(peers: List<GossipSimPeer>) {
        peers.forEach { it.inboundMessages.clear() }
    }

    private fun calcGossipStats(peers: List<GossipSimPeer>, msgSentTime: Long): GossipStats {
        val stats = StatsFactory.DEFAULT.createStats()
        val missingPeers = mutableListOf<GossipSimPeer>()
        peers.forEach {
            if (it.lastMsg != null) {
                stats.addValue(it.lastMsgTime - msgSentTime)
            } else {
                if (missingPeers.size < MaxMissingPeers) missingPeers += it
            }
        }
        return GossipStats(stats, missingPeers)
    }
}
