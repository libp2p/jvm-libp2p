package io.libp2p.simulate.gossip

import io.libp2p.simulate.Network
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.delay.bandwidth.AccurateBandwidthTracker
import io.libp2p.simulate.generateAndConnect
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimConnection
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import java.util.*

typealias GossipRouterBuilderFactory = (SimPeerId) -> SimGossipRouterBuilder
typealias GossipSimPeerModifier = (SimPeerId, GossipSimPeer) -> Unit

class GossipSimNetwork(
    val cfg: GossipSimConfig,
    val routerBuilderFactory: GossipRouterBuilderFactory = { SimGossipRouterBuilder() },
    val simPeerModifier: GossipSimPeerModifier = { _, _ -> }
) {
    val peers = sortedMapOf<SimPeerId, GossipSimPeer>()
    lateinit var network: Network

    val timeController = TimeControllerImpl()
    val commonRnd = Random(cfg.randomSeed)
    val commonExecutor = ControlledExecutorServiceImpl(timeController)

    protected fun createSimPeer(number: SimPeerId): GossipSimPeer {
        val peerConfig = cfg.peerConfigs[number]

        val routerBuilder = routerBuilderFactory(number).also {
            it.protocol = peerConfig.gossipProtocol
            it.params = peerConfig.gossipParams
            it.scoreParams = peerConfig.gossipScoreParams
            it.additionalHeartbeatDelay = peerConfig.additionalHeartbeatDelay
        }

        val simPeer = GossipSimPeer(number, commonRnd, peerConfig.gossipProtocol).also { simPeer ->
            simPeer.routerBuilder = routerBuilder
            simPeer.simExecutor = commonExecutor
            simPeer.currentTime = { timeController.time }
            simPeer.msgSizeEstimator = cfg.messageGenerator.sizeEstimator
            simPeer.inboundBandwidth =
                AccurateBandwidthTracker(
                    peerConfig.bandwidth.inbound,
                    simPeer.simExecutor,
                    simPeer.currentTime,
                    name = "[$simPeer]-in"
                )
            simPeer.outboundBandwidth =
                AccurateBandwidthTracker(
                    peerConfig.bandwidth.inbound,
                    simPeer.simExecutor,
                    simPeer.currentTime,
                    name = "[$simPeer]-in"
                )
            simPeerModifier(number, simPeer)
        }
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
            val latency = cfg.latencyDelayGenerator(it as StreamSimConnection)
            it.connectionLatency = latency
        }
    }
}
