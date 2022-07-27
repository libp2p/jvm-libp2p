package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.uShortToBytesBigEndian
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
open class GossipScoreBenchmark {

    val timeController = TimeControllerImpl()
    val controlledExecutor = ControlledExecutorServiceImpl().also { it.setTimeController(timeController) }
    val score = DefaultGossipScore(executor = controlledExecutor, curTimeMillis = { timeController.time })

    val peerCount = 5000
    val connectedCount = 2000
    val peerIds = List(peerCount) { PeerId.random() }
    val peerAddresses = List(peerCount) {
        Multiaddr.empty()
            .withComponent(Protocol.IP4, it.toBytesBigEndian())
            .withComponent(Protocol.TCP, 9000.uShortToBytesBigEndian())
    }

    init {
        (0 until peerCount).forEach {
            score.notifyConnected(peerIds[it], peerAddresses[it])
        }
        (connectedCount until peerCount).forEach {
            score.notifyDisconnected(peerIds[it])
        }
    }

    @Benchmark
    fun allScores(bh: Blackhole) {
        for (i in (0 until connectedCount)) {
            val s = score.score(peerIds[i])
            bh.consume(s)
        }
    }
}
