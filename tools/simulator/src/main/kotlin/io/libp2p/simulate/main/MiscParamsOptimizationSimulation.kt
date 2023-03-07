package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.Topology
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.milliseconds
import io.libp2p.simulate.stats.Stats
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.WritableStats
import io.libp2p.simulate.stats.collect.gossip.GossipMessageResult
import io.libp2p.simulate.stats.collect.gossip.GossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.gossip.getGossipPubDeliveryResult
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.*
import java.lang.Integer.max
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.collections.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

fun main() {
    MiscParamsOptimizationSimulation().runAll()
}

class MiscParamsOptimizationSimulation {

    val topic = Topic("Topic-1")

    data class GossipStats(
        val msgDelay: Stats,
        val someMissingPeers: List<GossipSimPeer> = emptyList()
    )

    data class FlatSimConfig(
        val totalPeers: Int = 10000,
        val badPeers: Int = 0,

        val gossipD: Int = 6,
        val gossipDLow: Int = 3,
        val gossipDHigh: Int = 12,
        val gossipDLazy: Int = 6,
        val gossipAdvertise: Int = 3,
        val gossipHistory: Int = 5,
        val gossipHeartbeat: Duration = 1.seconds,
        val gossipHeartbeatAddDelay: RandomDistribution<Duration> = RandomDistribution.const(Duration.ZERO),
        val gossipValidationDelay: Duration = ZERO,

        val avrgMessageSize: Int = 32 * 1024,
        val topology: Topology = RandomNPeers(10),
        val latency: RandomDistribution<Duration> = RandomDistribution.const(Duration.ZERO),
        val peersTimeShift: RandomDistribution<Duration> = RandomDistribution.const(Duration.ZERO)
    )

    data class SimOptions(
        val warmUpDelay: Duration = 5.seconds,
        val zeroHeartbeatsDelay: Duration = 500.milliseconds, // 0 to skip this step
        val manyHeartbeatsDelay: Duration = 30.seconds,
        val generatedNetworksCount: Int = 1,
        val sentMessageCount: Int = 10,
        val startRandomSeed: Long = 0,
        val iterationThreadsCount: Int = 1,
        val parallelIterationsCount: Int = 1,
        val measureTCPFramesOverhead: Boolean = true
    ) {
        fun isZeroHeartbeatsEnabled(): Boolean = zeroHeartbeatsDelay > ZERO
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

    fun runAll() {
        println("Running testResultStabilityAgainstNetworkSize()...")
        testResultStabilityAgainstNetworkSize()
        println("Running testBFT()...")
        testBFT()
        println("Running testBFTOfPeerConnections()...")
        testBFTOfPeerConnections()
        println("Running testDOptimization()...")
        testDOptimization()
        println("Running testSizeDLazyOptimization()...")
        testSizeDLazyOptimization()
        println("Running testHeartbeatPeriod()...")
        testHeartbeatPeriod()
        println("All complete!")
    }

    fun testResultStabilityAgainstNetworkSize() {
        val cfgs = sequence {
            for (totalPeers in arrayOf(1000, 5000, 10000, 20000, 30000))
                yield(
                    FlatSimConfig(
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
            generatedNetworksCount = 2,
            sentMessageCount = 3,
            startRandomSeed = 2
        )

        sim(cfgs, opt)
    }

    fun testBFT() {
        val totalPeers = 1000
        val cfgs = sequence {
            for (badPeers in arrayOf(0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.93, 0.95, 0.97)) {
                yield(
                    FlatSimConfig(
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

    fun testBFTOfPeerConnections() {
        val cfgs = sequence {
            for (peerConnections in arrayOf(6, 8, 10, 12, 15, 17, 20, 25, 30, 40, 50, 60, 80, 100)) {
                yield(
                    FlatSimConfig(
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

    fun testDOptimization() {
        val cfgs = sequence {
            for (gossipD in arrayOf(1, 2, 3, 4, 5, 6, 7))
                yield(
                    FlatSimConfig(
                        totalPeers = 5000,
                        badPeers = 0,
                        topology = RandomNPeers(20),

                        gossipD = gossipD,
                        gossipDLow = max(1, gossipD - 1),
                        gossipDHigh = gossipD + 1,
                        gossipDLazy = 10,
                        gossipHeartbeatAddDelay = RandomDistribution.uniform(0, 1000).milliseconds()
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

    fun testSizeDLazyOptimization() {
        val cfgs = sequence {
            for (avrgMessageSize in arrayOf(32 * 1024, 512))
                for (gossipDLazy in arrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20))
                    yield(
                        FlatSimConfig(
                            totalPeers = 5000,
                            badPeers = 0,
                            topology = RandomNPeers(20),

                            gossipD = 6,
                            gossipDLow = 5,
                            gossipDHigh = 7,
                            gossipDLazy = gossipDLazy,
                            gossipHeartbeatAddDelay = RandomDistribution.uniform(0, 1000).milliseconds(),

                            avrgMessageSize = avrgMessageSize,
                            latency = RandomDistribution.uniform(1, 50).milliseconds()
                        )
                    )
        }
        val opt = SimOptions(
            zeroHeartbeatsDelay = ZERO,
            generatedNetworksCount = 10,
            sentMessageCount = 3,
            startRandomSeed = 3,
            parallelIterationsCount = 4
        )

        sim(cfgs, opt)
    }

    fun testHeartbeatPeriod() {
        val cfgs = sequence {
            for (gossipHeartbeat in arrayOf(1000, 500, 300, 100, 50, 30, 10))
                yield(
                    FlatSimConfig(
                        totalPeers = 5000,
                        badPeers = 4500,
                        topology = RandomNPeers(20),

                        gossipD = 6,
                        gossipDLow = 3,
                        gossipDHigh = 12,
                        gossipDLazy = 10,
                        gossipHeartbeat = gossipHeartbeat.milliseconds,
                        gossipHeartbeatAddDelay = RandomDistribution.uniform(0, 1000).milliseconds(),
                        gossipHistory = 100, // increase history to serve low latency IWANT requests

                        latency = RandomDistribution.uniform(1, 50).milliseconds()
                    )
                )
        }
        val opt = SimOptions(
            warmUpDelay = 10.seconds,
            zeroHeartbeatsDelay = 0.milliseconds,
            manyHeartbeatsDelay = 30.seconds,
            generatedNetworksCount = 10,
            sentMessageCount = 3,
            parallelIterationsCount = 4
        )

        sim(cfgs, opt)
    }

    fun sim(cfg: Sequence<FlatSimConfig>, opt: SimOptions): List<SimDetailedResult> {
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

    fun sim(cfg: FlatSimConfig, opt: SimOptions): SimDetailedResult {
        val gossipParams = GossipParams(
            D = cfg.gossipD,
            DLow = cfg.gossipDLow,
            DHigh = cfg.gossipDHigh,
            DLazy = cfg.gossipDLazy,
            gossipSize = cfg.gossipAdvertise,
            gossipHistoryLength = cfg.gossipHistory,
            heartbeatInterval = cfg.gossipHeartbeat.toJavaDuration(),
        )

        val ret = SimDetailedResult()
        for (n in 0 until opt.generatedNetworksCount) {
            val gossipSimConfig = GossipSimConfig(
                totalPeers = cfg.totalPeers,
                topics = listOf(topic),
                gossipParams = gossipParams,
                additionalHeartbeatDelay = cfg.gossipHeartbeatAddDelay,
                messageGenerator = averagePubSubMsgSizeEstimator(cfg.avrgMessageSize, opt.measureTCPFramesOverhead),
                bandwidthGenerator = constantBandwidthGenerator(Bandwidth.mbitsPerSec(1024)),
                latencyDelayGenerator = LatencyDistribution.createUniform(cfg.latency).toLatencyGenerator(),
                messageValidationGenerator = { peer, _ ->
                    val validationResult =
                        if (peer.simPeerId > cfg.totalPeers - cfg.badPeers) {
                            ValidationResult.Ignore
                        } else {
                            ValidationResult.Valid
                        }
                    MessageValidation(cfg.gossipValidationDelay, validationResult)
                },
                topology = cfg.topology,
                peersTimeShift = cfg.peersTimeShift,
                warmUpDelay = opt.warmUpDelay,
                startRandomSeed = opt.startRandomSeed + n,
            )

            val simNetwork = GossipSimNetwork(gossipSimConfig)
            println("Creating peers...")
            simNetwork.createAllPeers()
            println("Connecting peers...")
            simNetwork.connectAllPeers()
            println("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())

            println("Creating simulation...")
            val simulation = GossipSimulation(gossipSimConfig, simNetwork)

            data class NetworkStats(
                val msgCount: Long,
                val traffic: Long
            ) {
                operator fun minus(other: NetworkStats) =
                    NetworkStats(msgCount - other.msgCount, traffic - other.traffic)
                operator fun plus(other: NetworkStats) =
                    NetworkStats(msgCount + other.msgCount, traffic + other.traffic)
            }
            fun getNetStats(result: GossipMessageResult): NetworkStats {
                return NetworkStats(result.getTotalMessageCount().toLong(), result.getTotalTraffic())
            }

            println("Initial stat: " + getNetStats(simulation.gossipMessageCollector.gatherResult()))
            simulation.clearAllMessages()

            for (i in 0 until opt.sentMessageCount) {
                println("Sending message #$i...")

                simulation.publishMessage(i)

                if (opt.isZeroHeartbeatsEnabled()) {
                    simulation.forwardTime(opt.zeroHeartbeatsDelay)
                    run {
                        val messageResult = simulation.gossipMessageCollector.gatherResult()
                        val gossipPubDeliveryResult: GossipPubDeliveryResult = messageResult.getGossipPubDeliveryResult()
                        val ns = getNetStats(messageResult)
                        val gs = calcGossipStats(simNetwork.peers.values, gossipPubDeliveryResult)
                        ret.zeroHeartbeats.packetCountPerMessage.addValue(ns.msgCount.toDouble() / (cfg.totalPeers - 1))
                        ret.zeroHeartbeats.trafficPerMessage.addValue(ns.traffic.toDouble() / (cfg.totalPeers - 1))
                        ret.zeroHeartbeats.deliverDelay.addAllValues(gossipPubDeliveryResult.deliveryDelays)
                        ret.zeroHeartbeats.deliveredPart.addValue(gs.msgDelay.getCount().toDouble() / (cfg.totalPeers - 1))
                        println("Zero heartbeats: $ns\t\t$gs")
                    }
                }

                simulation.forwardTime(opt.manyHeartbeatsDelay)

                run {
                    val messageResult = simulation.gossipMessageCollector.gatherResult()
                    val gossipPubDeliveryResult: GossipPubDeliveryResult = messageResult.getGossipPubDeliveryResult()
                    val ns = getNetStats(messageResult)
                    val gs = calcGossipStats(simNetwork.peers.values, gossipPubDeliveryResult)
                    ret.manyHeartbeats.packetCountPerMessage.addValue(ns.msgCount.toDouble() / (cfg.totalPeers - 1))
                    ret.manyHeartbeats.trafficPerMessage.addValue(ns.traffic.toDouble() / (cfg.totalPeers - 1))
                    ret.manyHeartbeats.deliverDelay.addAllValues(gossipPubDeliveryResult.deliveryDelays)
                    ret.manyHeartbeats.deliveredPart.addValue(gs.msgDelay.getCount().toDouble() / (cfg.totalPeers - 1))
                    println("Many heartbeats: $ns\t\t$gs")
                }

                val t2 = simulation.currentTimeSupplier()
                simulation.forwardTime(opt.manyHeartbeatsDelay)
                val messageResult = simulation.gossipMessageCollector
                    .gatherResult()
                    .slice(t2)
                val nsDiff = getNetStats(messageResult)
                println("Empty time: $nsDiff")

                simulation.clearAllMessages()
            }
        }
        return ret
    }

    private fun calcGossipStats(allPeers: Collection<GossipSimPeer>, gossipPubDeliveryResult: GossipPubDeliveryResult): GossipStats {
        val delayStats = StatsFactory.DEFAULT.createStats(
            gossipPubDeliveryResult.deliveryDelays
        )
        val receivedPeers = gossipPubDeliveryResult.deliveries
            .flatMap {
                listOf(it.toPeer, it.origMsg.fromPeer)
            }
            .toSet()
        return GossipStats(delayStats, allPeers - receivedPeers)
    }
}
