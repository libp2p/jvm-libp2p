package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.cappedDouble
import io.libp2p.etc.types.createLRUMap
import io.libp2p.etc.types.millis
import io.libp2p.etc.util.P2PService
import pubsub.pb.Rpc
import java.util.Optional
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class GossipScore(
    val params: GossipScoreParams = GossipScoreParams(),
    val executor: ScheduledExecutorService,
    val curTimeMillis: () -> Long
) {

    inner class TopicScores(val topic: Topic) {
        private val params = topicParams[topic]

        var joinedMeshTimeMillis: Long = 0
        var firstMessageDeliveries: Double by cappedDouble(
            0.0,
            this@GossipScore.peerParams.decayToZero,
            params.firstMessageDeliveriesCap
        )
        var meshMessageDeliveries: Double by cappedDouble(
            0.0,
            this@GossipScore.peerParams.decayToZero,
            params.meshMessageDeliveriesCap
        )
        var meshFailurePenalty: Double by cappedDouble(0.0, this@GossipScore.peerParams.decayToZero)
        var invalidMessages: Double by cappedDouble(0.0, this@GossipScore.peerParams.decayToZero)

        fun inMesh() = joinedMeshTimeMillis > 0

        fun meshTimeNorm() = min(
            (if (inMesh()) curTimeMillis() - joinedMeshTimeMillis else 0).toDouble() / params.timeInMeshQuantum.toMillis(),
            params.timeInMeshCap
        )

        fun isMeshMessageDeliveriesActive() =
            inMesh() && ((curTimeMillis() - joinedMeshTimeMillis).millis > params.meshMessageDeliveriesActivation)

        fun meshMessageDeliveriesDeficit() =
            if (isMeshMessageDeliveriesActive())
                max(0.0, params.meshMessageDeliveriesThreshold - meshMessageDeliveries)
            else 0.0

        fun meshMessageDeliveriesDeficitSqr() = meshMessageDeliveriesDeficit().pow(2)

        fun calcTopicScore(): Double {
            val p1 = meshTimeNorm()
            val p2 = firstMessageDeliveries
            val p3 = meshMessageDeliveriesDeficitSqr()
            val p3b = meshFailurePenalty
            val p4 = invalidMessages.pow(2)
            val ret = params.topicWeight * (
                    p1 * params.timeInMeshWeight +
                            p2 * params.firstMessageDeliveriesWeight +
                            p3 * params.meshMessageDeliveriesWeight +
                            p3b * params.meshFailurePenaltyWeight +
                            p4 * params.invalidMessageDeliveriesWeight
                    )
            return ret
        }

        fun decayScores() {
            firstMessageDeliveries *= params.firstMessageDeliveriesDecay
            meshMessageDeliveries *= params.meshMessageDeliveriesDecay
            meshFailurePenalty *= params.meshFailurePenaltyDecay
            invalidMessages *= params.invalidMessageDeliveriesDecay
        }
    }

    inner class PeerScores {
        val ips = mutableSetOf<String>()
        var connectedTimeMillis: Long = 0
        var disconnectedTimeMillis: Long = 0

        val topicScores = mutableMapOf<Topic, TopicScores>()
        var behaviorPenalty: Double by cappedDouble(0.0, peerParams.decayToZero)

        fun isConnected() = connectedTimeMillis > 0 && disconnectedTimeMillis == 0L
        fun isDisconnected() = disconnectedTimeMillis > 0
        fun getDisconnectDuration() =
            if (isDisconnected()) (curTimeMillis() - disconnectedTimeMillis).millis
            else throw IllegalStateException("Peer is not disconnected")
    }

    val peerParams = params.peerScoreParams
    val topicParams = params.topicsScoreParams

    private val validationTime: MutableMap<Rpc.Message, Long> = createLRUMap(1024)
    val peerScores = mutableMapOf<PeerId, PeerScores>()

    val refreshTask: ScheduledFuture<*>

    init {
        val refreshPeriod = peerParams.decayInterval.toMillis()
        refreshTask = executor.scheduleAtFixedRate({ refreshScores() }, refreshPeriod, refreshPeriod, TimeUnit.MILLISECONDS)
    }

    private fun getPeerScores(peer: P2PService.PeerHandler) =
        peerScores.computeIfAbsent(peer.peerId) { PeerScores() }

    private fun getTopicScores(peer: P2PService.PeerHandler, topic: Topic) =
        getPeerScores(peer).topicScores.computeIfAbsent(topic) { TopicScores(it) }

    private fun isInMesh(peer: P2PService.PeerHandler, topic: Topic) = getTopicScores(peer, topic).inMesh()

    fun score(peer: P2PService.PeerHandler): Double {
        val peerScore = getPeerScores(peer)
        val topicsScore = min(
            if (peerParams.topicScoreCap > 0) peerParams.topicScoreCap else Double.MAX_VALUE,
            peerScore.topicScores.values.map { it.calcTopicScore() }.sum()
        )
        val appScore = peerParams.appSpecificScore(peer.peerId) * peerParams.appSpecificWeight

        val peersInIp: Int = peer.getIP()?.let { thisIp ->
            if (peerParams.ipWhitelisted(thisIp)) 0 else
                peerScores.values.count { thisIp in it.ips }
        } ?: 0
        val ipColocationPenalty = max(
            0,
            (peersInIp - peerParams.ipColocationFactorThreshold)
        ).toDouble().pow(2) * peerParams.ipColocationFactorWeight

        val routerPenalty = peerScore.behaviorPenalty * peerParams.behaviourPenaltyWeight

        return topicsScore + appScore + ipColocationPenalty + routerPenalty
    }

    fun refreshScores() {
        peerScores.values.removeIf { it.isDisconnected() && it.getDisconnectDuration() > peerParams.retainScore }
        peerScores.values.forEach {
            it.topicScores.values.forEach { it.decayScores() }
            it.behaviorPenalty *= peerParams.behaviourPenaltyDecay
        }
    }

    fun notifyDisconnected(peer: P2PService.PeerHandler) {
        getPeerScores(peer).topicScores.filter { it.value.inMesh() }.forEach { t, _ ->
            notifyPruned(peer, t)
        }

        getPeerScores(peer).disconnectedTimeMillis = curTimeMillis()
    }

    fun notifyConnected(peer: P2PService.PeerHandler) {
        getPeerScores(peer).apply {
            connectedTimeMillis = curTimeMillis()
            peer.getIP()?.also { ips += it }
        }
    }

    fun notifyUnseenMessage(peer: P2PService.PeerHandler, msg: Rpc.Message) {
    }

    fun notifySeenMessage(peer: P2PService.PeerHandler, msg: Rpc.Message, validationResult: Optional<ValidationResult>) {
        msg.topicIDsList
            .filter { isInMesh(peer, it) }
            .forEach { topic ->
                val topicScores = getTopicScores(peer, topic)
                val durationAfterValidation = (curTimeMillis() - (validationTime[msg] ?: 0)).millis
                when {
                    validationResult.isPresent && validationResult.get() == ValidationResult.Invalid ->
                        topicScores.invalidMessages++
                    !validationResult.isPresent
                            || durationAfterValidation < topicParams[topic].meshMessageDeliveryWindow ->
                        topicScores.meshMessageDeliveries++
                }
            }
    }

    fun notifyUnseenInvalidMessage(peer: P2PService.PeerHandler, msg: Rpc.Message) {
        validationTime[msg] = curTimeMillis()
        msg.topicIDsList.forEach { getTopicScores(peer, it).invalidMessages++ }
    }

    fun notifyUnseenValidMessage(peer: P2PService.PeerHandler, msg: Rpc.Message) {
        validationTime[msg] = curTimeMillis()
        msg.topicIDsList
            .onEach { getTopicScores(peer, it).firstMessageDeliveries++ }
            .filter { isInMesh(peer, it) }
            .onEach { getTopicScores(peer, it).meshMessageDeliveries++ }
    }

    fun notifyMeshed(peer: P2PService.PeerHandler, topic: Topic) {
        val topicScores = getTopicScores(peer, topic)
        topicScores.joinedMeshTimeMillis = curTimeMillis()
    }

    fun notifyPruned(peer: P2PService.PeerHandler, topic: Topic) {
        val topicScores = getTopicScores(peer, topic)
        topicScores.meshFailurePenalty += topicScores.meshMessageDeliveriesDeficitSqr()
        topicScores.joinedMeshTimeMillis = 0
    }

    fun notifyRouterMisbehavior(peer: P2PService.PeerHandler, count: Int) {
        getPeerScores(peer).behaviorPenalty += count
    }

    fun stop() {
        refreshTask.cancel(false)
    }
}