package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.LRUCollections
import io.libp2p.etc.types.cappedDouble
import io.libp2p.etc.types.millis
import io.libp2p.etc.util.P2PService
import pubsub.pb.Rpc
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class GossipScore(val executor: ScheduledExecutorService, val curTime: () -> Long) {

    inner class TopicScores(val topic: Topic) {
        private val params = topicsParams[topic]

        var joinedMeshTime: Long = 0
        var firstMessageDeliveries: Double by cappedDouble(
            0.0,
            globalParams.decayToZero,
            params.FirstMessageDeliveriesCap
        )
        var meshMessageDeliveries: Double by cappedDouble(
            0.0,
            globalParams.decayToZero,
            params.MeshMessageDeliveriesCap
        )
        var meshFailurePenalty: Double by cappedDouble(0.0, globalParams.decayToZero)
        var invalidMessages: Double by cappedDouble(0.0, globalParams.decayToZero)

        fun inMesh() = joinedMeshTime > 0

        fun meshTimeNorm() = min(
            (if (inMesh()) curTime() - joinedMeshTime else 0).toDouble() / params.TimeInMeshQuantum.toMillis(),
            params.TimeInMeshCap
        )

        fun isMeshMessageDeliveriesActive() =
            inMesh() && ((curTime() - joinedMeshTime).millis > params.MeshMessageDeliveriesActivation)

        fun meshMessageDeliveriesDeficit() =
            if (isMeshMessageDeliveriesActive())
                max(0.0, params.MeshMessageDeliveriesThreshold - meshMessageDeliveries)
            else 0.0

        fun meshMessageDeliveriesDeficitSqr() = meshMessageDeliveriesDeficit().pow(2)

        fun calcTopicScore(): Double {
            val p1 = meshTimeNorm()
            val p2 = firstMessageDeliveries
            val p3 = meshMessageDeliveriesDeficitSqr()
            val p3b = meshFailurePenalty
            val p4 = invalidMessages.pow(2)
            return params.TopicWeight * (
                    p1 * params.TimeInMeshWeight +
                            p2 * params.FirstMessageDeliveriesWeight +
                            p3 * params.MeshMessageDeliveriesWeight +
                            p3b * params.MeshFailurePenaltyWeight +
                            p4 * params.InvalidMessageDeliveriesWeight
                    )
        }

        fun decayScores() {
            firstMessageDeliveries *= params.FirstMessageDeliveriesDecay
            meshMessageDeliveries *= params.MeshMessageDeliveriesDecay
            meshFailurePenalty *= params.MeshFailurePenaltyDecay
            invalidMessages *= params.InvalidMessageDeliveriesDecay
        }
    }

    inner class PeerScores {
        val ips = mutableSetOf<String>()
        var connectedTime: Long = 0
        var disconnectedTime: Long = 0

        val topicScores = mutableMapOf<Topic, TopicScores>()
        var behaviorPenalty: Double by cappedDouble(0.0, globalParams.decayToZero)

        fun isConnected() = connectedTime > 0 && disconnectedTime == 0L
        fun isDisconnected() = disconnectedTime > 0
        fun getDisconnectDuration() =
            if (isDisconnected()) (curTime() - disconnectedTime).millis
            else throw IllegalStateException("Peer is not disconnected")
    }

    private val validationTime: MutableMap<Rpc.Message, Long> = LRUCollections.createMap(1024)
    val peerScores = mutableMapOf<PeerId, PeerScores>()
    val topicsParams = GossipParamsExtTopics()
    val scoreParams = GossipParamsExtPeerScoring()
    val globalParams = GossipParamsExtPeerTopicScoring()
    val refreshTask: ScheduledFuture<*>

    init {
        val refreshPeriod = globalParams.decayInterval.toMillis()
        refreshTask = executor.scheduleAtFixedRate({ refreshScores() }, refreshPeriod, refreshPeriod, TimeUnit.MILLISECONDS)
    }

    private fun getPeerScores(peer: P2PService.PeerHandler) =
        peerScores.computeIfAbsent(peer.peerId()) { PeerScores() }

    private fun getTopicScores(peer: P2PService.PeerHandler, topic: Topic) =
        getPeerScores(peer).topicScores.computeIfAbsent(topic) { TopicScores(it) }

    private fun isInMesh(peer: P2PService.PeerHandler, topic: Topic) = getTopicScores(peer, topic).inMesh()

    fun score(peer: P2PService.PeerHandler): Double {
        val peerScore = getPeerScores(peer)
        val topicsScore = min(
            scoreParams.topicScoreCap,
            peerScore.topicScores.values.map { it.calcTopicScore() }.sum()
        )
        val appScore = scoreParams.appSpecificScore(peer.peerId()) * scoreParams.appSpecificWeight

        val peersInIp: Int = peer.getIP()?.let { thisIp ->
            if (scoreParams.ipWhitelisted(thisIp)) 0 else
                peerScores.values.count { thisIp in it.ips }
        } ?: 0
        val ipColocationPenalty = max(
            0,
            (peersInIp - scoreParams.ipColocationFactorThreshold)
        ).toDouble().pow(2) * scoreParams.ipColocationFactorWeight

        val routerPenalty = peerScore.behaviorPenalty * scoreParams.behaviourPenaltyWeight

        return topicsScore + appScore + ipColocationPenalty + routerPenalty
    }

    fun refreshScores() {
        peerScores.values.removeIf { it.isDisconnected() && it.getDisconnectDuration() > globalParams.retainScore }
        peerScores.values.forEach {
            it.topicScores.values.forEach { it.decayScores() }
            it.behaviorPenalty *= scoreParams.behaviourPenaltyDecay
        }
    }

    fun notifyDisconnected(peer: P2PService.PeerHandler) {
        getPeerScores(peer).topicScores.filter { it.value.inMesh() }.forEach { t, _ ->
            notifyPruned(peer, t)
        }

        getPeerScores(peer).disconnectedTime = curTime()
    }

    fun notifyConnected(peer: P2PService.PeerHandler) {
        getPeerScores(peer).apply {
            connectedTime = curTime()
            peer.getIP()?.also { ips += it }
        }
    }

    fun notifyUnseenMessage(peer: P2PService.PeerHandler, msg: Rpc.Message) {
    }

    fun notifySeenMessage(peer: P2PService.PeerHandler, msg: Rpc.Message, validationResult: ValidationResult) {
        msg.topicIDsList
            .filter { isInMesh(peer, it) }
            .forEach { topic ->
                val topicScores = getTopicScores(peer, topic)
                val durationAfterValidation = (curTime() - (validationTime[msg] ?: 0)).millis
                when {
                    validationResult == ValidationResult.Invalid -> topicScores.invalidMessages++
                    validationResult == ValidationResult.Pending
                            || durationAfterValidation < topicsParams[topic].MeshMessageDeliveryWindow ->
                        topicScores.meshMessageDeliveries++
                }
            }
    }

    fun notifyUnseenInvalidMessage(peer: P2PService.PeerHandler, msg: Rpc.Message) {
        validationTime[msg] = curTime()
        msg.topicIDsList.forEach { getTopicScores(peer, it).invalidMessages++ }
    }

    fun notifyUnseenValidMessage(peer: P2PService.PeerHandler, msg: Rpc.Message) {
        validationTime[msg] = curTime()
        msg.topicIDsList
            .onEach { getTopicScores(peer, it).firstMessageDeliveries++ }
            .filter { isInMesh(peer, it) }
            .onEach { getTopicScores(peer, it).meshMessageDeliveries++ }
    }

    fun notifyMeshed(peer: P2PService.PeerHandler, topic: Topic) {
        val topicScores = getTopicScores(peer, topic)
        topicScores.joinedMeshTime = curTime()
    }

    fun notifyPruned(peer: P2PService.PeerHandler, topic: Topic) {
        val topicScores = getTopicScores(peer, topic)
        topicScores.meshFailurePenalty += topicScores.meshMessageDeliveriesDeficitSqr()
        topicScores.joinedMeshTime = 0
    }

    fun notifyRouterMisbehavior(peer: P2PService.PeerHandler, count: Int) {
        getPeerScores(peer).behaviorPenalty += count
    }

    fun stop() {
        refreshTask.cancel(false)
    }
}