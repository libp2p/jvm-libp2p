package io.libp2p.simulate.gossip

import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.Network
import io.libp2p.simulate.generateAndConnect
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class GossipSimNetwork(
    val cfg: GossipSimConfig,
    val routerFactory: (Int) -> SimGossipRouterBuilder,
    val simPeerModifier: (Int, GossipSimPeer) -> Unit = { _, _ -> }
) {
    val peers = sortedMapOf<Int, GossipSimPeer>()
    lateinit var network: Network

    val timeController = TimeControllerImpl()
    val commonRnd = Random(cfg.startRandomSeed)
    val commonExecutor = ControlledExecutorServiceImpl(timeController)

    protected val peerExecutors =
        if (cfg.iterationThreadsCount > 1)
            (0 until cfg.iterationThreadsCount).map { Executors.newSingleThreadScheduledExecutor() }
        else
            listOf(Executor { it.run() })

    var simPeerFactory: (Int, SimGossipRouterBuilder) -> GossipSimPeer = { number, router ->
        GossipSimPeer(cfg.topics, number.toString(), commonRnd).apply {
            routerBuilder = router

            val delegateExecutor = peerExecutors[number % peerExecutors.size]
            simExecutor = ControlledExecutorServiceImpl(delegateExecutor, timeController)
            currentTime = { timeController.time }
            msgSizeEstimator = cfg.messageGenerator.sizeEstimator
            validationDelay = cfg.gossipValidationDelay

            start()
        }
    }

    protected fun createSimPeer(number: Int): GossipSimPeer {
        val router = routerFactory(number).also {
            it.currentTimeSuppluer = { timeController.time }
            it.serializeMessagesToBytes = false
        }

        val simPeer = simPeerFactory(number, router)
        val (inbound, outbound) = cfg.bandwidthGenerator(simPeer)
        simPeer.inboundBandwidth = inbound
        simPeer.outboundBandwidth = outbound
        simPeerModifier(number, simPeer)
        return simPeer
    }

    fun createAllPeers() {
        peers += (0 until cfg.totalPeers).map {
            it to createSimPeer(it)
        }
    }

    fun connectAllPeers() {
        cfg.topology.random = commonRnd
        network = cfg.topology.generateAndConnect(peers.values.toList())
        network.activeConnections.forEach {
            val latency = cfg.latencyGenerator(it as StreamSimConnection)
            it.connectionLatency = latency
        }
    }
}
