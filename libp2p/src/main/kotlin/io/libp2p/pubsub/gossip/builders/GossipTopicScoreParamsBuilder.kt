package io.libp2p.pubsub.gossip.builders

import io.libp2p.pubsub.gossip.GossipTopicScoreParams
import java.time.Duration

class GossipTopicScoreParamsBuilder() {
    private var topicWeight: Double? = null

    private var timeInMeshWeight: Double? = null

    private var timeInMeshQuantum: Duration? = null

    private var timeInMeshCap: Double? = null

    private var firstMessageDeliveriesWeight: Double? = null

    private var firstMessageDeliveriesDecay: Double? = null

    private var firstMessageDeliveriesCap: Double? = null

    private var meshMessageDeliveriesWeight: Double? = null

    private var meshMessageDeliveriesDecay: Double? = null

    private var meshMessageDeliveriesThreshold: Double? = null

    private var meshMessageDeliveriesCap: Double? = null

    private var meshMessageDeliveriesActivation: Duration? = null

    private var meshMessageDeliveryWindow: Duration? = null

    private var meshFailurePenaltyWeight: Double? = null

    private var meshFailurePenaltyDecay: Double? = null

    private var invalidMessageDeliveriesWeight: Double? = null

    private var invalidMessageDeliveriesDecay: Double? = null

    constructor(source: GossipTopicScoreParams) : this() {
        this.topicWeight = source.topicWeight
        this.timeInMeshWeight = source.timeInMeshWeight
        this.timeInMeshQuantum = source.timeInMeshQuantum
        this.timeInMeshCap = source.timeInMeshCap
        this.firstMessageDeliveriesWeight = source.firstMessageDeliveriesWeight
        this.firstMessageDeliveriesDecay = source.firstMessageDeliveriesDecay
        this.firstMessageDeliveriesCap = source.firstMessageDeliveriesCap
        this.meshMessageDeliveriesWeight = source.meshMessageDeliveriesWeight
        this.meshMessageDeliveriesDecay = source.meshMessageDeliveriesDecay
        this.meshMessageDeliveriesThreshold = source.meshMessageDeliveriesThreshold
        this.meshMessageDeliveriesCap = source.meshMessageDeliveriesCap
        this.meshMessageDeliveriesActivation = source.meshMessageDeliveriesActivation
        this.meshMessageDeliveryWindow = source.meshMessageDeliveryWindow
        this.meshFailurePenaltyWeight = source.meshFailurePenaltyWeight
        this.meshFailurePenaltyDecay = source.meshFailurePenaltyDecay
        this.invalidMessageDeliveriesWeight = source.invalidMessageDeliveriesWeight
        this.invalidMessageDeliveriesDecay = source.invalidMessageDeliveriesDecay
    }

    fun topicWeight(value: Double): GossipTopicScoreParamsBuilder = apply { topicWeight = value }

    fun timeInMeshWeight(value: Double): GossipTopicScoreParamsBuilder = apply {
        timeInMeshWeight = value
    }

    fun timeInMeshQuantum(value: Duration): GossipTopicScoreParamsBuilder = apply {
        timeInMeshQuantum = value
    }

    fun timeInMeshCap(value: Double): GossipTopicScoreParamsBuilder = apply { timeInMeshCap = value }

    fun firstMessageDeliveriesWeight(value: Double): GossipTopicScoreParamsBuilder = apply {
        firstMessageDeliveriesWeight = value
    }

    fun firstMessageDeliveriesDecay(value: Double): GossipTopicScoreParamsBuilder = apply {
        firstMessageDeliveriesDecay = value
    }

    fun firstMessageDeliveriesCap(value: Double): GossipTopicScoreParamsBuilder = apply {
        firstMessageDeliveriesCap = value
    }

    fun meshMessageDeliveriesWeight(value: Double): GossipTopicScoreParamsBuilder = apply {
        meshMessageDeliveriesWeight = value
    }

    fun meshMessageDeliveriesDecay(value: Double): GossipTopicScoreParamsBuilder = apply {
        meshMessageDeliveriesDecay = value
    }

    fun meshMessageDeliveriesThreshold(value: Double): GossipTopicScoreParamsBuilder = apply {
        meshMessageDeliveriesThreshold = value
    }

    fun meshMessageDeliveriesCap(value: Double): GossipTopicScoreParamsBuilder = apply {
        meshMessageDeliveriesCap = value
    }

    fun meshMessageDeliveriesActivation(value: Duration): GossipTopicScoreParamsBuilder = apply {
        meshMessageDeliveriesActivation = value
    }

    fun meshMessageDeliveryWindow(value: Duration): GossipTopicScoreParamsBuilder = apply {
        meshMessageDeliveryWindow = value
    }

    fun meshFailurePenaltyWeight(value: Double): GossipTopicScoreParamsBuilder = apply {
        meshFailurePenaltyWeight = value
    }

    fun meshFailurePenaltyDecay(value: Double): GossipTopicScoreParamsBuilder = apply {
        meshFailurePenaltyDecay = value
    }

    fun invalidMessageDeliveriesWeight(value: Double): GossipTopicScoreParamsBuilder = apply {
        invalidMessageDeliveriesWeight = value
    }

    fun invalidMessageDeliveriesDecay(value: Double): GossipTopicScoreParamsBuilder = apply {
        invalidMessageDeliveriesDecay = value
    }

    fun build(): GossipTopicScoreParams {
        checkRequiredFields()
        return GossipTopicScoreParams(
            topicWeight = topicWeight!!,
            timeInMeshWeight = timeInMeshWeight!!,
            timeInMeshQuantum = timeInMeshQuantum!!,
            timeInMeshCap = timeInMeshCap!!,
            firstMessageDeliveriesWeight = firstMessageDeliveriesWeight!!,
            firstMessageDeliveriesDecay = firstMessageDeliveriesDecay!!,
            firstMessageDeliveriesCap = firstMessageDeliveriesCap!!,
            meshMessageDeliveriesWeight = meshMessageDeliveriesWeight!!,
            meshMessageDeliveriesDecay = meshMessageDeliveriesDecay!!,
            meshMessageDeliveriesThreshold = meshMessageDeliveriesThreshold!!,
            meshMessageDeliveriesCap = meshMessageDeliveriesCap!!,
            meshMessageDeliveriesActivation = meshMessageDeliveriesActivation!!,
            meshMessageDeliveryWindow = meshMessageDeliveryWindow!!,
            meshFailurePenaltyWeight = meshFailurePenaltyWeight!!,
            meshFailurePenaltyDecay = meshFailurePenaltyDecay!!,
            invalidMessageDeliveriesWeight = invalidMessageDeliveriesWeight!!,
            invalidMessageDeliveriesDecay = invalidMessageDeliveriesDecay!!
        )
    }

    private fun checkRequiredFields() {
        check(topicWeight != null, { "topicWeight must not be null" })
        check(timeInMeshWeight != null, { "timeInMeshWeight must not be null" })
        check(timeInMeshQuantum != null, { "timeInMeshQuantum must not be null" })
        check(timeInMeshCap != null, { "timeInMeshCap must not be null" })
        check(
            firstMessageDeliveriesWeight != null,
            { "firstMessageDeliveriesWeight must not be null" }
        )
        check(
            firstMessageDeliveriesDecay != null,
            { "firstMessageDeliveriesDecay must not be null" }
        )
        check(firstMessageDeliveriesCap != null, { "firstMessageDeliveriesCap must not be null" })
        check(
            meshMessageDeliveriesWeight != null,
            { "meshMessageDeliveriesWeight must not be null" }
        )
        check(meshMessageDeliveriesDecay != null, { "meshMessageDeliveriesDecay must not be null" })
        check(
            meshMessageDeliveriesThreshold != null,
            { "meshMessageDeliveriesThreshold must not be null" }
        )
        check(meshMessageDeliveriesCap != null, { "meshMessageDeliveriesCap must not be null" })
        check(
            meshMessageDeliveriesActivation != null,
            { "meshMessageDeliveriesActivation must not be null" }
        )
        check(meshMessageDeliveryWindow != null, { "meshMessageDeliveryWindow must not be null" })
        check(meshFailurePenaltyWeight != null, { "meshFailurePenaltyWeight must not be null" })
        check(meshFailurePenaltyDecay != null, { "meshFailurePenaltyDecay must not be null" })
        check(
            invalidMessageDeliveriesWeight != null,
            { "invalidMessageDeliveriesWeight must not be null" }
        )
        check(
            invalidMessageDeliveriesDecay != null,
            { "invalidMessageDeliveriesDecay must not be null" }
        )
    }
}
