package io.libp2p.pubsub.gossip

import com.google.common.annotations.VisibleForTesting
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.etc.types.cappedDouble
import io.libp2p.etc.types.createLRUMap
import io.libp2p.etc.types.millis
import io.libp2p.pubsub.MessageRejectReason
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.Topic
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

interface GossipScore {

    fun updateTopicParams(topicScoreParams: Map<String, GossipTopicScoreParams>)

    fun score(peerId: PeerId): Double

    fun getCachedScore(peerId: PeerId): Double
}

class DefaultGossipScore(
    val params: GossipScoreParams = GossipScoreParams(),
    val executor: ScheduledExecutorService,
    val curTimeMillis: () -> Long
) : GossipScore, GossipRouterEventListener {

    data class PeerIP(val ip4String: String) {
        companion object {
            fun fromMultiaddr(multiaddr: Multiaddr): PeerIP? =
                multiaddr.getFirstComponent(Protocol.IP4)?.stringValue?.let { PeerIP(it) }
        }
    }

    inner class TopicScores(val topic: Topic, private val peer: PeerScores) {
        private val params: GossipTopicScoreParams
            get() = topicParams[topic]
        private val recalcMaxDurationMillis = params.timeInMeshQuantum.toMillis()
        private var cachedScore: Double = 0.0
        private var cacheValid: Boolean = false
        private var cachedParams = params
        private var cachedParamsVersion = topicParams.version
        private var prevTime = curTimeMillis()

        /** Time from which [calcTopicScore] recomputes the score even if nothing else changed */
        val cacheExpiryTimeMillis: Long
            get() = prevTime + recalcMaxDurationMillis

        var joinedMeshTimeMillis: Long = 0
            set(value) {
                field = value
                invalidateCache()
            }

        var firstMessageDeliveries: Double by cappedDouble(
            0.0,
            this@DefaultGossipScore.peerParams.decayToZero,
            { params.firstMessageDeliveriesCap },
            { invalidateCache() }
        )
        var meshMessageDeliveries: Double by cappedDouble(
            0.0,
            this@DefaultGossipScore.peerParams.decayToZero,
            { params.meshMessageDeliveriesCap },
            { invalidateCache() }
        )
        var meshFailurePenalty: Double by cappedDouble(
            0.0,
            this@DefaultGossipScore.peerParams.decayToZero,
            { _ -> invalidateCache() }
        )

        var invalidMessages: Double by cappedDouble(
            0.0,
            this@DefaultGossipScore.peerParams.decayToZero,
            { _ -> invalidateCache() }
        )

        private fun invalidateCache() {
            cacheValid = false
            peer.invalidateTopicsScore()
        }

        fun inMesh() = joinedMeshTimeMillis > 0

        private fun meshTimeNorm(curTime: Long) = min(
            (if (inMesh()) curTime - joinedMeshTimeMillis else 0).toDouble() / params.timeInMeshQuantum.toMillis(),
            params.timeInMeshCap
        )

        private fun isMeshMessageDeliveriesActive(curTime: Long) =
            inMesh() && ((curTime - joinedMeshTimeMillis).millis > params.meshMessageDeliveriesActivation)

        private fun meshMessageDeliveriesDeficit(curTime: Long) =
            if (isMeshMessageDeliveriesActive(curTime)) {
                max(0.0, params.meshMessageDeliveriesThreshold - meshMessageDeliveries)
            } else {
                0.0
            }

        fun meshMessageDeliveriesDeficitSqr(curTime: Long = curTimeMillis()) =
            meshMessageDeliveriesDeficit(curTime).pow(2)

        fun calcTopicScore(curTime: Long = curTimeMillis()): Double {
            val paramsVersion = topicParams.version
            if (cacheValid && curTime - prevTime < recalcMaxDurationMillis) {
                if (paramsVersion == cachedParamsVersion) return cachedScore
                // topic params were set since, but maybe not this topic's: only then recompute
                if (params === cachedParams) {
                    cachedParamsVersion = paramsVersion
                    return cachedScore
                }
            }
            cachedParams = params
            cachedParamsVersion = paramsVersion
            prevTime = curTime
            val p1 = meshTimeNorm(curTime)
            val p2 = firstMessageDeliveries
            val p3 = meshMessageDeliveriesDeficitSqr(curTime)
            val p3b = meshFailurePenalty
            val p4 = invalidMessages.pow(2)
            cachedScore = params.topicWeight * (
                p1 * params.timeInMeshWeight +
                    p2 * params.firstMessageDeliveriesWeight +
                    p3 * params.meshMessageDeliveriesWeight +
                    p3b * params.meshFailurePenaltyWeight +
                    p4 * params.invalidMessageDeliveriesWeight
                )
            cacheValid = true
            return cachedScore
        }

        fun decayScores() {
            firstMessageDeliveries *= params.firstMessageDeliveriesDecay
            meshMessageDeliveries *= params.meshMessageDeliveriesDecay
            meshFailurePenalty *= params.meshFailurePenaltyDecay
            invalidMessages *= params.invalidMessageDeliveriesDecay
        }
    }

    inner class PeerScores {
        // Cached values are accessed across threads
        @Volatile
        var cachedScore: Double = 0.0

        var connectedTimeMillis: Long = 0
        var disconnectedTimeMillis: Long = 0

        private val mutableTopicScores = mutableMapOf<Topic, TopicScores>()
        val topicScores: Map<Topic, TopicScores> = mutableTopicScores
        var behaviorPenalty: Double by cappedDouble(0.0, peerParams.decayToZero)

        // The topics in topicScores order; an ArrayList iterates faster than the map values
        private val topicList = ArrayList<TopicScores>()

        // The sum of the topic scores, reused while every topic would return its own cached value:
        // none was invalidated or added, no topic params were set and no topic cache has expired
        private var topicsScore: Double = 0.0
        private var topicsScoreValidUntilMillis: Long = Long.MIN_VALUE
        private var topicsScoreParamsVersion: Long = topicParams.version

        fun getOrCreateTopicScores(topic: Topic): TopicScores =
            mutableTopicScores.getOrPut(topic) {
                TopicScores(topic, this).also {
                    topicList.add(it)
                    invalidateTopicsScore()
                }
            }

        internal fun invalidateTopicsScore() {
            topicsScoreValidUntilMillis = Long.MIN_VALUE
        }

        /** Returns the same value as `topicScores.values.sumOf { it.calcTopicScore(curTime) }` */
        fun calcTopicsScore(curTime: Long): Double {
            val paramsVersion = topicParams.version
            if (curTime < topicsScoreValidUntilMillis && paramsVersion == topicsScoreParamsVersion) {
                return topicsScore
            }
            var sum = 0.0
            var validUntil = Long.MAX_VALUE
            for (i in topicList.indices) {
                val topic = topicList[i]
                sum += topic.calcTopicScore(curTime)
                validUntil = min(validUntil, topic.cacheExpiryTimeMillis)
            }
            topicsScore = sum
            topicsScoreValidUntilMillis = validUntil
            topicsScoreParamsVersion = paramsVersion
            return sum
        }

        fun isConnected() = connectedTimeMillis > 0 && disconnectedTimeMillis == 0L
        fun isDisconnected() = disconnectedTimeMillis > 0
        fun getDisconnectDuration() =
            if (isDisconnected()) {
                (curTimeMillis() - disconnectedTimeMillis).millis
            } else {
                throw IllegalStateException("Peer is not disconnected")
            }
    }

    val peerParams = params.peerScoreParams
    val topicParams = params.topicsScoreParams

    private val validationTime: MutableMap<PubsubMessage, Long> = createLRUMap(1024)

    @VisibleForTesting
    val peerScores = ConcurrentHashMap<PeerId, PeerScores>()
    private val peerIdToIP = mutableMapOf<PeerId, PeerIP>()
    private val peerIPToId = PeerColocations()

    private val refreshTask: ScheduledFuture<*>

    init {
        val refreshPeriod = peerParams.decayInterval.toMillis()
        refreshTask = executor.scheduleAtFixedRate({ refreshScores() }, refreshPeriod, refreshPeriod, TimeUnit.MILLISECONDS)
    }

    private fun getPeerScores(peerId: PeerId) =
        peerScores.computeIfAbsent(peerId) { PeerScores() }

    private fun getPeerIp(peerId: PeerId): PeerIP? = peerIdToIP[peerId]

    private fun getTopicScores(peerId: PeerId, topic: Topic) =
        getPeerScores(peerId).getOrCreateTopicScores(topic)

    private fun isInMesh(peerId: PeerId, topic: Topic) = getTopicScores(peerId, topic).inMesh()

    override fun updateTopicParams(topicScoreParams: Map<String, GossipTopicScoreParams>) {
        executor.execute {
            for (topicScoreParam in topicScoreParams) {
                params.topicsScoreParams.setTopicParams(topicScoreParam.key, topicScoreParam.value)
            }
        }
    }

    override fun score(peerId: PeerId): Double {
        val peerScore = getPeerScores(peerId)
        val topicsScore = min(
            if (peerParams.topicScoreCap > 0) peerParams.topicScoreCap else Double.MAX_VALUE,
            peerScore.calcTopicsScore(curTimeMillis())
        )
        val appScore = peerParams.appSpecificScore(peerId) * peerParams.appSpecificWeight

        val peersInIp: Int = getPeerIp(peerId)?.let { peerIPToId.getPeerCountForIp(it) } ?: 0
        val ipColocationPenalty = max(
            0,
            (peersInIp - peerParams.ipColocationFactorThreshold)
        ).toDouble().pow(2) * peerParams.ipColocationFactorWeight

        val behaviorExcess = peerScore.behaviorPenalty - peerParams.behaviourPenaltyThreshold
        val routerPenalty =
            if (behaviorExcess < 0) {
                0.0
            } else {
                behaviorExcess.pow(2) * peerParams.behaviourPenaltyWeight
            }

        val computedScore = topicsScore + appScore + ipColocationPenalty + routerPenalty
        peerScore.cachedScore = computedScore
        return computedScore
    }

    @VisibleForTesting
    fun refreshScores() {
        val peersToBury = peerScores
            .filterValues {
                it.isDisconnected() && it.getDisconnectDuration() > peerParams.retainScore
            }
            .keys
        peersToBury.forEach { peerId ->
            peerIdToIP.remove(peerId)?.also { peerIp ->
                peerIPToId.remove(peerId, peerIp)
            }
        }
        peerScores -= peersToBury

        peerScores.values.forEach {
            it.topicScores.values.forEach { it.decayScores() }
            it.behaviorPenalty *= peerParams.behaviourPenaltyDecay
        }
    }

    override fun getCachedScore(peerId: PeerId): Double {
        return peerScores[peerId]?.cachedScore ?: 0.0
    }

    override fun notifyDisconnected(peerId: PeerId) {
        getPeerScores(peerId).topicScores.filter { it.value.inMesh() }.forEach { t, _ ->
            notifyPruned(peerId, t)
        }

        getPeerScores(peerId).disconnectedTimeMillis = curTimeMillis()
    }

    override fun notifyConnected(peerId: PeerId, peerAddress: Multiaddr) {
        val ipAddress = PeerIP.fromMultiaddr(peerAddress)
        ipAddress?.also { peerIp ->
            val maybePeerIP = peerIdToIP[peerId]
            maybePeerIP?.also {
                peerIPToId.remove(peerId, peerIp)
            }
            peerIdToIP[peerId] = peerIp
        }
        getPeerScores(peerId).apply {
            connectedTimeMillis = curTimeMillis()
            getPeerIp(peerId)?.also { peerIPToId.add(peerId, it) }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override fun notifyUnseenMessage(peerId: PeerId, msg: PubsubMessage) {
    }

    override fun notifySeenMessage(peerId: PeerId, msg: PubsubMessage, validationResult: Optional<ValidationResult>) {
        msg.topics
            .filter { isInMesh(peerId, it) }
            .forEach { topic ->
                val topicScores = getTopicScores(peerId, topic)
                val durationAfterValidation = (curTimeMillis() - (validationTime[msg] ?: 0)).millis
                when {
                    validationResult.isPresent && validationResult.get() == ValidationResult.Invalid ->
                        topicScores.invalidMessages++
                    !validationResult.isPresent ||
                        durationAfterValidation < topicParams[topic].meshMessageDeliveryWindow ->
                        topicScores.meshMessageDeliveries++
                }
            }
    }

    override fun notifyUnseenInvalidMessage(
        peerId: PeerId,
        msg: PubsubMessage,
        reason: MessageRejectReason
    ) {
        // Both reject reasons are penalised identically; the distinction is for observability only.
        validationTime[msg] = curTimeMillis()
        msg.topics.forEach { getTopicScores(peerId, it).invalidMessages++ }
    }

    @Deprecated("Call the overload that also receives the MessageRejectReason")
    override fun notifyUnseenInvalidMessage(peerId: PeerId, msg: PubsubMessage) =
        notifyUnseenInvalidMessage(peerId, msg, MessageRejectReason.ValidationFailed)

    override fun notifyUnseenValidMessage(peerId: PeerId, msg: PubsubMessage) {
        validationTime[msg] = curTimeMillis()
        msg.topics
            .onEach { getTopicScores(peerId, it).firstMessageDeliveries++ }
            .filter { isInMesh(peerId, it) }
            .onEach { getTopicScores(peerId, it).meshMessageDeliveries++ }
    }

    override fun notifyMeshed(peerId: PeerId, topic: Topic) {
        val topicScores = getTopicScores(peerId, topic)
        topicScores.joinedMeshTimeMillis = curTimeMillis()
    }

    override fun notifyPruned(peerId: PeerId, topic: Topic) {
        val topicScores = getTopicScores(peerId, topic)
        topicScores.meshFailurePenalty += topicScores.meshMessageDeliveriesDeficitSqr()
        topicScores.joinedMeshTimeMillis = 0
    }

    override fun notifyRouterMisbehavior(peerId: PeerId, count: Int) {
        getPeerScores(peerId).behaviorPenalty += count
    }

    override fun notifySlowPeer(peerId: PeerId) {
        notifyRouterMisbehavior(peerId, 1)
    }

    fun stop() {
        refreshTask.cancel(false)
    }

    internal class PeerColocations {
        private val colocatedPeers = mutableMapOf<PeerIP, MutableSet<PeerId>>()

        fun add(peerId: PeerId, peerIp: PeerIP) {
            colocatedPeers.computeIfAbsent(peerIp) { mutableSetOf() } += peerId
        }

        fun remove(peerId: PeerId, peerIp: PeerIP) {
            colocatedPeers[peerIp]?.also { it -= peerId }
        }

        fun getPeerCountForIp(ip: PeerIP) = colocatedPeers[ip]?.size ?: 0
    }
}
