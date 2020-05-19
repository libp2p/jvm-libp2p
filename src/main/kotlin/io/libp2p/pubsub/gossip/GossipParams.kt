package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import java.time.Duration

typealias Weight = Double

data class GossipParamsCore(
    val D: Int = 3,
    val DLow: Int = D * 3 / 2,
    val DHigh: Int = D * 2,
    val DGossip: Int = D,
    val fanoutTTL: Duration = 60.seconds,
    val gossipSize: Int = 3,
    val gossipHistoryLength: Int = 5,
    val heartbeatInterval: Duration = 1.seconds
)

data class GossipParamsExtGlobal(
    val pruneBackoff: Duration = 1.millis,
    val floodPublish: Boolean = true,
    val gossipFactor: Double = 0.25,
    val dScore: Int = 5 // depends on D
)

data class GossipParamsExtPeerTopicScoring(
    val gossipThreshold: Double = 1.0,
    val publishThreshold: Double = 2.0,
    val graylistThreshold: Double = 3.0,
    val acceptPXThreshold: Double = 4.0,
    val opportunisticGraftThreshold: Double = 5.0,
    val decayInterval: Duration = 1.minutes,
    val decayToZero: Double = 0.01,
    val retainScore: Duration = 10.minutes
) {
    init {
        // TODO validation
    }
}

data class GossipParamsExtPeerScoring(
    val topicScoreCap: Double = 10000.0,
    val appSpecificScore: (PeerId) -> Double = { 0.0 },
    val appSpecificWeight: Weight = 1.0,
    val ipWhitelisted: (String) -> Boolean = { false },
    val ipColocationFactorWeight: Weight = 1.0,
    val ipColocationFactorThreshold: Int = 100,
    val behaviourPenaltyWeight: Weight = 1.0,
    val behaviourPenaltyDecay: Double = 1.0
) {
    init {
        // TODO validation
    }
}

class GossipParamsExtTopics {
    private val defaultParams: GossipParamsExtTopic = GossipParamsExtTopic()
    private val topicParams: MutableMap<Topic, GossipParamsExtTopic> = mutableMapOf()

    operator fun get(topic: Topic) = topicParams.getOrDefault(topic, defaultParams)
}

data class GossipParamsExtTopic(
    val TopicWeight: Weight = 1.0,
    // P₁
    val TimeInMeshWeight: Weight = 1.0,
    val TimeInMeshQuantum: Duration = 1.seconds,
    val TimeInMeshCap: Double = 100.0,
    // P₂
    val FirstMessageDeliveriesWeight: Weight = 1.0,
    val FirstMessageDeliveriesDecay: Double = 1.0,
    val FirstMessageDeliveriesCap: Double = 100.0,
    // P₃
    val MeshMessageDeliveriesWeight: Weight = 1.0,
    val MeshMessageDeliveriesDecay: Double = 1.0,
    val MeshMessageDeliveriesThreshold: Double = 100.0,
    val MeshMessageDeliveriesCap: Double = 100.0,
    val MeshMessageDeliveriesActivation: Duration = 1.minutes,
    val MeshMessageDeliveryWindow: Duration = 10.millis,
    // P₃b
    val MeshFailurePenaltyWeight: Weight = 1.0,
    val MeshFailurePenaltyDecay: Double = 100.0,
    // P₄
    val InvalidMessageDeliveriesWeight: Weight = 1.0,
    val InvalidMessageDeliveriesDecay: Double = 100.0
) {
    init {
        // TODO validation
    }
}
