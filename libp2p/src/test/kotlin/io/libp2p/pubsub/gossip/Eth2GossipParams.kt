package io.libp2p.pubsub.gossip

import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.etc.types.times

// Spec constants
val SlotDuration = 12.seconds
val SlotsPerEpoch = 32
val EpochDuration = SlotDuration * SlotsPerEpoch
val BlocksTopic = "/eth2/00000000/beacon_block/ssz_snappy"
val AggrAttestTopic = "/eth2/00000000/beacon_aggregate_and_proof/ssz_snappy"
val AttestTopicPrefix = "/eth2/00000000/beacon_attestation_"
val AttestTopicSuffix = "/ssz_snappy"

val Eth2DefaultGossipParams = GossipParams(
    D = 8,
    DLow = 6,
    DHigh = 12,
    DLazy = 8,

    pruneBackoff = 1.minutes,
    floodPublish = true,
    gossipFactor = 0.25,
    DScore = 4,
    DOut = 2,
    // TODO peersInPx
    graftFloodThreshold = 10.seconds,
    opportunisticGraftTicks = 85,
    opportunisticGraftPeers = 2,
    gossipRetransmission = 3,
    maxIHaveLength = 5000,
    maxIHaveMessages = 5,
    iWantFollowupTime = 3.seconds
)

val Eth2DefaultBlockTopicParams = GossipTopicScoreParams(
    topicWeight = 0.5,
    timeInMeshWeight = 0.0324,
    timeInMeshQuantum = SlotDuration,
    timeInMeshCap = 300.0,
    firstMessageDeliveriesWeight = 1.0,
    firstMessageDeliveriesDecay = 0.9928,
    firstMessageDeliveriesCap = 23.0,
    meshMessageDeliveriesWeight = -0.724,
    meshMessageDeliveriesDecay = 0.9928,
    meshMessageDeliveriesThreshold = 14.0,
    meshMessageDeliveriesCap = 139.0,
    meshMessageDeliveriesActivation = EpochDuration * 4,
    meshMessageDeliveryWindow = 2.seconds,
    meshFailurePenaltyWeight = -0.724,
    meshFailurePenaltyDecay = 0.9928,
    invalidMessageDeliveriesWeight = -142.0,
    invalidMessageDeliveriesDecay = 0.9971
)

val Eth2DefaultAggrAttestTopicParams = GossipTopicScoreParams(
    // TODO
)

val Eth2DefaultAttestTopicParams = GossipTopicScoreParams(
    // TODO
)

private val subnetTopicParams =
    (0..63).map { "$AttestTopicPrefix$it$AttestTopicSuffix" to Eth2DefaultAttestTopicParams }.toMap()
val Eth2DefaultTopicsParams = GossipTopicsScoreParams(
    topicParamsMap =
    mapOf(
        BlocksTopic to Eth2DefaultBlockTopicParams,
        AggrAttestTopic to Eth2DefaultAggrAttestTopicParams
    ) + subnetTopicParams
)

val Eth2DefaultPeerScoreParams = GossipPeerScoreParams(
    decayInterval = SlotDuration,
    decayToZero = 0.1,
    retainScore = EpochDuration * 100,
    appSpecificWeight = 1.0,
    ipColocationFactorWeight = -35.5,
    ipColocationFactorThreshold = 10,
    behaviourPenaltyThreshold = 6.0,
    behaviourPenaltyWeight = -15.92,
    behaviourPenaltyDecay = 0.9857,
    topicScoreCap = 35.5
)

val Eth2DefaultScoreParams = GossipScoreParams(
    peerScoreParams = Eth2DefaultPeerScoreParams,
    topicsScoreParams = Eth2DefaultTopicsParams,
    gossipThreshold = -4000.0,
    publishThreshold = -8000.0,
    graylistThreshold = -16000.0,
    acceptPXThreshold = 100.0,
    opportunisticGraftThreshold = 5.0
)
