package io.libp2p.pubsub.gossip

import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.LRUCollections
import io.libp2p.etc.types.cappedDouble
import io.libp2p.etc.types.millis
import io.libp2p.etc.util.P2PService
import pubsub.pb.Rpc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class GossipScore(val curTime: () -> Long) {

    inner class TopicScores(val topic: Topic) {
        private val params = topicsParams[topic]

        var joinedMeshTime: Long = 0
        var firstMessageDeliveries: Double by cappedDouble(0.0, globalParams.decayToZero, params.FirstMessageDeliveriesCap)
        var meshMessageDeliveries: Double by cappedDouble(0.0, globalParams.decayToZero, params.MeshMessageDeliveriesCap)
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
        val topicScores = mutableMapOf<Topic, TopicScores>()
        var behaviorPenalty: Double by cappedDouble(0.0, globalParams.decayToZero)
    }

    private val validationTime: MutableMap<Rpc.Message, Long> = LRUCollections.createMap(1024)
    val peerScores = mutableMapOf<P2PService.PeerHandler, PeerScores>()
    val topicsParams = GossipParamsExtTopics()
    val scoreParams = GossipParamsExtPeerScoring()
    val globalParams = GossipParamsExtPeerTopicScoring()

    private fun getPeerScores(peer: P2PService.PeerHandler) =
        peerScores.computeIfAbsent(peer) { PeerScores() }

    private fun getTopicScores(peer: P2PService.PeerHandler, topic: Topic) =
        getPeerScores(peer).topicScores.computeIfAbsent(topic) { TopicScores(it) }

    private fun isInMesh(peer: P2PService.PeerHandler, topic: Topic) = getTopicScores(peer, topic).inMesh()

    fun score(peer: P2PService.PeerHandler): Double {
        val peerScore = getPeerScores(peer)
        val topicsScore = min(scoreParams.topicScoreCap,
            peerScore.topicScores.values.map { it.calcTopicScore() }.sum()
        )
        val appScore = scoreParams.appSpecificScore(peer.peerId()) * scoreParams.appSpecificWeight

        val peersInIp: Int = peer.getIP()?.let { thisIp ->
            if (scoreParams.ipWhitelisted(thisIp)) 0 else
                peerScores.keys.count { it.getIP() == thisIp }
        } ?: 0
        val ipColocationPenalty = max(
            0,
            (peersInIp - scoreParams.ipColocationFactorThreshold)
        ).toDouble().pow(2) * scoreParams.ipColocationFactorWeight

        val routerPenalty = peerScore.behaviorPenalty * scoreParams.behaviourPenaltyWeight

        return topicsScore + appScore + ipColocationPenalty + routerPenalty
    }

    fun refreshScores() {
        peerScores.values.forEach {
            it.topicScores.values.forEach { it.decayScores() }
            it.behaviorPenalty *= scoreParams.behaviourPenaltyDecay
        }
    }

    fun notifyDisconnected(peer: P2PService.PeerHandler) {
        peerScores[peer]?.topicScores?.filter { it.value.inMesh() }?.forEach { t, _ ->
            notifyPruned(peer, t)
        }
    }

    fun notifyConnected(peer: P2PService.PeerHandler) {
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
}