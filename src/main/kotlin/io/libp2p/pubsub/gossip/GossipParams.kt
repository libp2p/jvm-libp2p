package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.millis
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
    val gossipThreshold: Double,
    val publishThreshold: Double,
    val graylistThreshold: Double,
    val acceptPXThreshold: Double,
    val opportunisticGraftThreshold: Double,
    val decayInterval: Duration,
    val decayToZero: Double,
    val retainScore: Duration
) {
    init {
        // TODO validation
    }
}

data class GossipParamsExtPeerScoring(
    val appSpecificWeight: Weight,
    val ipColocationFactorWeight: Weight,
    val ipColocationFactorThreshold: Double,
    val behaviourPenaltyWeight: Weight,
    val behaviourPenaltyDecay: Double
) {
    init {
        // TODO validation
    }
}

data class GossipParamsExtTopic(
    val TopicWeight: Weight,
    // P₁
    val TimeInMeshWeight: Weight,
    val TimeInMeshQuantum: Duration,
    val TimeInMeshCap: Double,
    // P₂
    val FirstMessageDeliveriesWeight: Weight,
    val FirstMessageDeliveriesDecay: Double,
    val FirstMessageDeliveriesCap: Double,
    // P₃
    val MeshMessageDeliveriesWeight: Weight,
    val MeshMessageDeliveriesDecay: Double,
    val MeshMessageDeliveriesThreshold: Double,
    val MeshMessageDeliveriesCap: Double,
    val MeshMessageDeliveriesActivation: Duration,
    val MeshMessageDeliveryWindow: Duration,
    // P₃b
    val MeshFailurePenaltyWeight: Weight,
    val MeshFailurePenaltyDecay: Double,
    // P₄
    val InvalidMessageDeliveriesWeight: Weight,
    val InvalidMessageDeliveriesDecay: Double
) {
    init {
        // TODO validation
    }
}
