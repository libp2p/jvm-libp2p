package io.libp2p.pubsub.gossip.builders

import io.libp2p.core.PeerId
import io.libp2p.pubsub.gossip.GossipPeerScoreParams
import java.time.Duration

class GossipPeerScoreParamsBuilder() {
    private var topicScoreCap: Double? = null

    private var isDirect: Function1<PeerId, Boolean>? = null

    private var appSpecificScore: Function1<PeerId, Double>? = null

    private var appSpecificWeight: Double? = null

    private var ipWhitelisted: Function1<String, Boolean>? = null

    private var ipColocationFactorWeight: Double? = null

    private var ipColocationFactorThreshold: Int? = null

    private var behaviourPenaltyWeight: Double? = null

    private var behaviourPenaltyDecay: Double? = null

    private var behaviourPenaltyThreshold: Double? = null

    private var decayInterval: Duration? = null

    private var decayToZero: Double? = null

    private var retainScore: Duration? = null

    constructor(source: GossipPeerScoreParams) : this() {
        this.topicScoreCap = source.topicScoreCap
        this.isDirect = source.isDirect
        this.appSpecificScore = source.appSpecificScore
        this.appSpecificWeight = source.appSpecificWeight
        this.ipWhitelisted = source.ipWhitelisted
        this.ipColocationFactorWeight = source.ipColocationFactorWeight
        this.ipColocationFactorThreshold = source.ipColocationFactorThreshold
        this.behaviourPenaltyWeight = source.behaviourPenaltyWeight
        this.behaviourPenaltyDecay = source.behaviourPenaltyDecay
        this.behaviourPenaltyThreshold = source.behaviourPenaltyThreshold
        this.decayInterval = source.decayInterval
        this.decayToZero = source.decayToZero
        this.retainScore = source.retainScore
    }

    fun topicScoreCap(value: Double): GossipPeerScoreParamsBuilder = apply { topicScoreCap = value }

    fun isDirect(value: Function1<PeerId, Boolean>): GossipPeerScoreParamsBuilder = apply {
        isDirect = value
    }

    fun appSpecificScore(value: Function1<PeerId, Double>): GossipPeerScoreParamsBuilder = apply {
        appSpecificScore = value
    }

    fun appSpecificWeight(value: Double): GossipPeerScoreParamsBuilder = apply {
        appSpecificWeight = value
    }

    fun ipWhitelisted(value: Function1<String, Boolean>): GossipPeerScoreParamsBuilder = apply {
        ipWhitelisted = value
    }

    fun ipColocationFactorWeight(value: Double): GossipPeerScoreParamsBuilder = apply {
        ipColocationFactorWeight = value
    }

    fun ipColocationFactorThreshold(value: Int): GossipPeerScoreParamsBuilder = apply {
        ipColocationFactorThreshold = value
    }

    fun behaviourPenaltyWeight(value: Double): GossipPeerScoreParamsBuilder = apply {
        behaviourPenaltyWeight = value
    }

    fun behaviourPenaltyDecay(value: Double): GossipPeerScoreParamsBuilder = apply {
        behaviourPenaltyDecay = value
    }

    fun behaviourPenaltyThreshold(value: Double): GossipPeerScoreParamsBuilder = apply {
        behaviourPenaltyThreshold = value
    }

    fun decayInterval(value: Duration): GossipPeerScoreParamsBuilder = apply { decayInterval = value }

    fun decayToZero(value: Double): GossipPeerScoreParamsBuilder = apply { decayToZero = value }

    fun retainScore(value: Duration): GossipPeerScoreParamsBuilder = apply { retainScore = value }

    fun build(): GossipPeerScoreParams {
        checkRequiredFields()
        return GossipPeerScoreParams(
            topicScoreCap = topicScoreCap!!,
            isDirect = isDirect!!,
            appSpecificScore = appSpecificScore!!,
            appSpecificWeight = appSpecificWeight!!,
            ipWhitelisted = ipWhitelisted!!,
            ipColocationFactorWeight = ipColocationFactorWeight!!,
            ipColocationFactorThreshold = ipColocationFactorThreshold!!,
            behaviourPenaltyWeight = behaviourPenaltyWeight!!,
            behaviourPenaltyDecay = behaviourPenaltyDecay!!,
            behaviourPenaltyThreshold = behaviourPenaltyThreshold!!,
            decayInterval = decayInterval!!,
            decayToZero = decayToZero!!,
            retainScore = retainScore!!
        )
    }

    private fun checkRequiredFields() {
        check(topicScoreCap != null, { "topicScoreCap must not be null" })
        check(isDirect != null, { "isDirect must not be null" })
        check(appSpecificScore != null, { "appSpecificScore must not be null" })
        check(appSpecificWeight != null, { "appSpecificWeight must not be null" })
        check(ipWhitelisted != null, { "ipWhitelisted must not be null" })
        check(ipColocationFactorWeight != null, { "ipColocationFactorWeight must not be null" })
        check(
            ipColocationFactorThreshold != null,
            { "ipColocationFactorThreshold must not be null" }
        )
        check(behaviourPenaltyWeight != null, { "behaviourPenaltyWeight must not be null" })
        check(behaviourPenaltyDecay != null, { "behaviourPenaltyDecay must not be null" })
        check(behaviourPenaltyThreshold != null, { "behaviourPenaltyThreshold must not be null" })
        check(decayInterval != null, { "decayInterval must not be null" })
        check(decayToZero != null, { "decayToZero must not be null" })
        check(retainScore != null, { "retainScore must not be null" })
    }
}
