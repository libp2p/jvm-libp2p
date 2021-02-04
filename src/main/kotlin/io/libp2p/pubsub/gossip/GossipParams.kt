package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.millis
import io.libp2p.etc.types.minutes
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.DEFAULT_MAX_PUBSUB_MESSAGE_SIZE
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipPeerScoreParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipScoreParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipTopicScoreParamsBuilder
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

typealias Weight = Double

fun defaultDLow(D: Int) = D * 2 / 3
fun defaultDHigh(D: Int) = D * 2
fun defaultDLazy(D: Int) = D
fun defaultDScore(D: Int) = D * 2 / 3
fun defaultDOut(D: Int, DLow: Int) = min(D / 2, max(DLow - 1, 0))

/**
 * Parameters of Gossip 1.1 router
 */
data class GossipParams(
    /**
     * [D] sets the optimal degree for a GossipSub topic mesh. For example, if [D == 6],
     * each peer will want to have about six peers in their mesh for each topic they're subscribed to.
     * [D] should be set somewhere between [DLow] and [DHi].
     */
    val D: Int = 6,

    /**
     * [DLow] sets the lower bound on the number of peers we keep in a GossipSub topic mesh.
     * If we have fewer than [DLow] peers, we will attempt to graft some more into the mesh at
     * the next heartbeat.
     */
    val DLow: Int = defaultDLow(D),

    /**
     * [DHigh] sets the upper bound on the number of peers we keep in a GossipSub topic mesh.
     * If we have more than [DHigh] peers, we will select some to prune from the mesh at the next heartbeat.
     */
    val DHigh: Int = defaultDHigh(D),

    /**
     * [DScore] affects how peers are selected when pruning a mesh due to over subscription.
     * At least [DScore] of the retained peers will be high-scoring, while the remainder are
     * chosen randomly.
     */
    val DScore: Int = defaultDScore(D),

    /**
     * 	[DOut] sets the quota for the number of outbound connections to maintain in a topic mesh.
     * When the mesh is pruned due to over subscription, we make sure that we have outbound connections
     * to at least [DOut] of the survivor peers. This prevents sybil attackers from overwhelming
     * our mesh with incoming connections.
     *
     * [DOut] must be set below [DLow], and must not exceed [D / 2].
     */
    val DOut: Int = defaultDOut(D, DLow),

    /**
     * [DLazy] affects how many peers we will emit gossip to at each heartbeat.
     * We will send gossip to at least [DLazy] peers outside our mesh. The actual
     * number may be more, depending on GossipSubGossipFactor and how many peers we're
     * connected to.
     */
    val DLazy: Int = defaultDLazy(D),

    /**
     * [fanoutTTL] controls how long we keep track of the fanout state. If it's been
     * [fanoutTTL] since we've published to a topic that we're not subscribed to,
     * we'll delete the fanout map for that topic.
     */
    val fanoutTTL: Duration = 60.seconds,

    /**
     * [maxGossipMessageSize] determines the max acceptable gossip message size.  Messages larger than this will
     * be ignored.
     */
    val maxGossipMessageSize: Int = DEFAULT_MAX_PUBSUB_MESSAGE_SIZE,

    /**
     * [gossipSize] controls how many cached message ids we will advertise in
     * IHAVE gossip messages. When asked for our seen message IDs, we will return
     * only those from the most recent [gossipSize] heartbeats. The slack between
     * [gossipSize] and [gossipHistoryLength] allows us to avoid advertising messages
     * that will be expired by the time they're requested.
     *
     * [gossipSize] must be less than or equal to [gossipHistoryLength]
     */
    val gossipSize: Int = 3,

    /**
     * [gossipHistoryLength] controls the size of the message cache used for gossip.
     * The message cache will remember messages for [gossipHistoryLength] heartbeats.
     */
    val gossipHistoryLength: Int = 5,

    /**
     * [heartbeatInterval] controls the time between heartbeats.
     */
    val heartbeatInterval: Duration = 1.seconds,

    /**
     * Expiry time for cache of seen message ids
     */
    val seenTTL: Duration = 2.minutes,

    /**
     * [floodPublish] is a gossipsub router option that enables flood publishing.
     * When this is enabled, published messages are forwarded to all peers with score >=
     * to publishThreshold
     */
    val floodPublish: Boolean = false,

    /**
     * [gossipFactor] affects how many peers we will emit gossip to at each heartbeat.
     * We will send gossip to [gossipFactor * (total number of non-mesh peers)], or
     * [DLazy], whichever is greater.
     */
    val gossipFactor: Double = 0.25,

    /**
     * [opportunisticGraftPeers] is the number of peers to opportunistically graft.
     */
    val opportunisticGraftPeers: Int = 2,

    /**
     * [opportunisticGraftTicks] is the number of heartbeat ticks for attempting to improve the mesh
     * with opportunistic grafting. Every [opportunisticGraftTicks] we will attempt to select some
     * high-scoring mesh peers to replace lower-scoring ones, if the median score of our mesh peers falls
     * below a threshold (see https://godoc.org/github.com/libp2p/go-libp2p-pubsub#PeerScoreThresholds).
     */
    val opportunisticGraftTicks: Int = 60,

    /**
     * If a GRAFT comes before [graftFloodThreshold] has elapsed since the last PRUNE,
     * then there is an extra score penalty applied to the peer through P7.
     */
    val graftFloodThreshold: Duration = 10.seconds,

    /**
     * [maxPublishedMessages] is the maximum number of messages allowed in the publish list per
     * gossip message.
     */
    val maxPublishedMessages: Int? = null,

    /**
     * [maxTopicsPerPublishedMessage] is the maximum number of topics a given message can be published to.
     */
    val maxTopicsPerPublishedMessage: Int? = null,

    /**
     * [maxSubscriptions] is the maximum number of subscriptions allowed per gossip message.
     */
    val maxSubscriptions: Int? = null,

    /**
     * [maxIHaveLength] is the maximum number of messages to include in an IHAVE message.
     * Also controls the maximum number of IHAVE ids we will accept and request with IWANT from a
     * peer within a heartbeat, to protect from IHAVE floods. You should adjust this value from the
     * default if your system is pushing more than 5000 messages in [gossipSize] heartbeats;
     * with the defaults this is 1666 messages/s.
     */
    val maxIHaveLength: Int = 5000,

    /**
     *  [maxIHaveMessages] is the maximum number of IHAVE messages to accept from a peer within a heartbeat.
     */
    val maxIHaveMessages: Int = 10,

    /**
     * [maxIWantMessageIds] The maximum number of message ids that can be included across IWANT messages within
     * a single gossip message
     */
    val maxIWantMessageIds: Int? = null,

    /**
     * Time to wait for a message requested through IWANT following an IHAVE advertisement.
     * If the message is not received within this window, a broken promise is declared and
     * the router may apply behavioural penalties.
     */
    val iWantFollowupTime: Duration = 3.seconds,

    /**
     * [maxGraftMessages] is the maximum number of graft messages allowed per gossip message
     */
    val maxGraftMessages: Int? = null,

    /**
     * [maxPrunePeers] controls the number of peers to include in prune Peer eXchange.
     * When we prune a peer that's eligible for PX (has a good score, etc), we will try to
     * send them signed peer records for up to [maxPrunePeers] other peers that we
     * know of.
     */
    val maxPrunePeers: Int = 16,

    /**
     * [maxPeersPerPruneMessage] is the maximum number of peers allowed in an incoming prune message
     */
    val maxPeersPerPruneMessage: Int? = null,

    /**
     * [pruneBackoff] controls the backoff time for pruned peers. This is how long
     * a peer must wait before attempting to graft into our mesh again after being pruned.
     * When pruning a peer, we send them our value of [pruneBackoff] so they know
     * the minimum time to wait. Peers running older versions may not send a backoff time,
     * so if we receive a prune message without one, we will wait at least [pruneBackoff]
     * before attempting to re-graft.
     */
    val pruneBackoff: Duration = 1.minutes,

    /**
     * [maxPruneMessages] is the maximum number of prune messages allowed per gossip message
     */
    val maxPruneMessages: Int? = null,

    /**
     * [gossipRetransmission] controls how many times we will allow a peer to request
     * the same message id through IWANT gossip before we start ignoring them. This is designed
     * to prevent peers from spamming us with requests and wasting our resources.
     */
    val gossipRetransmission: Int = 3,

    /**
     * callback to notify outer system to which peers Gossip wants to be connected
     * The second parameter is a signed peer record: https://github.com/libp2p/specs/pull/217
     */
    val connectCallback: (PeerId, ByteArray) -> Unit = { _: PeerId, _: ByteArray -> }
) {
    init {
        check(DOut < DLow || (DOut == 0 && DLow == 0), "DOut should be < DLow or both 0")
        check(DOut <= D / 2, "DOut should be <= D/2")
        check(DLow <= D, "DLow should be <= D")
        check(DHigh >= D, "DHigh should be >= D")
        check(gossipFactor in 0.0..1.0, "gossipFactor should be in range [0.0, 1.1]")
    }

    companion object {
        @JvmStatic
        fun builder() = GossipParamsBuilder()
    }
}

/**
 * Parameters for Gossip peer scoring
 */
data class GossipScoreParams(

    /**
     * Peer specific params
     */
    val peerScoreParams: GossipPeerScoreParams = GossipPeerScoreParams(),

    /**
     * Topic specific params
     */
    val topicsScoreParams: GossipTopicsScoreParams = GossipTopicsScoreParams(),

    /**
     * [gossipThreshold] is the score threshold below which gossip propagation is supressed;
     * should be negative.
     */
    val gossipThreshold: Double = 0.0,

    /**
     * [publishThreshold] is the score threshold below which we shouldn't publish when using flood
     * publishing (also applies to fanout and floodsub peers); should be negative and <= [gossipThreshold].
     */
    val publishThreshold: Double = 0.0,

    /**
     * [graylistThreshold] is the score threshold below which message processing is supressed altogether,
     * implementing an effective graylist according to peer score; should be negative and <= [publishThreshold].
     */
    val graylistThreshold: Double = 0.0,

    /**
     * [acceptPXThreshold] is the score threshold below which PX will be ignored; this should be positive
     * and limited to scores attainable by bootstrappers and other trusted nodes.
     */
    val acceptPXThreshold: Double = 0.0,

    /**
     * [opportunisticGraftThreshold] is the median mesh score threshold before triggering opportunistic
     * grafting; this should have a small positive value.
     */
    val opportunisticGraftThreshold: Double = 0.0
) {
    init {
        check(gossipThreshold <= 0, "gossipThreshold should be < 0")
        check(publishThreshold <= gossipThreshold, "publishThreshold should be <= than gossipThreshold")
        check(graylistThreshold <= publishThreshold, "gossipThreshold should be < publishThreshold")
        check(acceptPXThreshold >= 0, "acceptPXThreshold should be > 0")
        check(opportunisticGraftThreshold >= 0, "opportunisticGraftThreshold should be > 0")
    }

    companion object {
        @JvmStatic
        fun builder() = GossipScoreParamsBuilder(GossipScoreParams())
    }
}

/**
 * Peer specific params
 */
data class GossipPeerScoreParams(

    /**
     * Aggregate topic score cap; this limits the total contribution of topics towards a positive
     * score. It must be positive (or 0 for no cap).
     */
    val topicScoreCap: Double = 0.0,

    /**
     * Callback supplied by the client code to determine direct peers
     * These peers are connected outside of the mesh, with all (valid)
     * message unconditionally forwarded to them. The client code should maintain open connections
     * to these peers. Note that the peering agreement should be reciprocal with direct peers
     * symmetrically configured at both ends.
     */
    val isDirect: (PeerId) -> Boolean = { false },

    /**
     * P5: Application-specific peer scoring
     */
    val appSpecificScore: (PeerId) -> Double = { 0.0 },

    /**
     * Weight of P₅, the application-specific score.
     * Must be positive, however score values may be negative.
     */
    val appSpecificWeight: Weight = 0.0,

    /**
     * Client callback which indicates if the IP address is whitelisted
     * No colocation penalties are applied to whitelisted IPs
     */
    val ipWhitelisted: (String) -> Boolean = { false },

    /**
     * P6: IP-colocation factor.
     * The parameter has an associated counter which counts the number of peers with the same IP.
     * If the number of peers in the same IP exceeds [ipColocationFactorThreshold], then the value
     * is the square of the difference, ie `(PeersInSameIP - ipColocationFactorThreshold)^2`.
     * If the number of peers in the same IP is less than the threshold, then the value is 0.
     * The weight of the parameter MUST be negative, unless you want to disable for testing.
     * Note: In order to simulate many IPs in a managable manner when testing, you can set the weight to 0
     *       thus disabling the IP colocation penalty.
     */
    val ipColocationFactorWeight: Weight = 0.0,

    /** See [ipColocationFactorWeight] */
    val ipColocationFactorThreshold: Int = 0,

    /**
     * P7: behavioural pattern penalties.
     * This parameter has an associated counter which tracks misbehaviour as detected by the
     * router. The router currently applies penalties for the following behaviors:
     * - attempting to re-graft before the prune backoff time has elapsed.
     * - not following up in IWANT requests for messages advertised with IHAVE.
     *
     * The value of the parameter is the square of the counter over the threshold,
     * which decays with  [behaviourPenaltyDecay].
     * The weight of the parameter MUST be negative (or zero to disable).
     */
    val behaviourPenaltyWeight: Weight = 0.0,

    /** See [behaviourPenaltyWeight] */
    val behaviourPenaltyDecay: Double = 0.9,

    /** See [behaviourPenaltyWeight] */
    val behaviourPenaltyThreshold: Double = 1.0,

    /** the decay interval for parameter counters. */
    val decayInterval: Duration = 1.minutes,

    /** counter value below which it is considered 0. */
    val decayToZero: Double = 0.0,

    /** time to remember counters for a disconnected peer. */
    val retainScore: Duration = 10.minutes
) {
    init {
        check(topicScoreCap >= 0.0, "topicScoreCap should be > 0")
        check(appSpecificWeight >= 0.0, "appSpecificWeight should be > 0")
        check(ipColocationFactorWeight <= 0.0, "ipColocationFactorWeight should be < 0")
        check(
            ipColocationFactorWeight == 0.0 || ipColocationFactorThreshold >= 1,
            "ipColocationFactorThreshold should be >= 1"
        )
        check(behaviourPenaltyWeight <= 0.0, "behaviourPenaltyWeight should be <= 0")
        check(
            behaviourPenaltyWeight == 0.0 || (behaviourPenaltyDecay > 0.0 && behaviourPenaltyDecay <= 1.0),
            "behaviourPenaltyDecay should be in range (0.0, 1.0]"
        )
        check(behaviourPenaltyThreshold >= 0.0, "behaviourPenaltyThreshold should be >= 0")
    }
    companion object {
        @JvmStatic
        fun builder() = GossipPeerScoreParamsBuilder(GossipPeerScoreParams())
    }
}

/**
 * The mapping `topic => GossipTopicScoreParams`
 * If params are not specified for a `topic` explicitly the [defaultParams] is returned
 *
 * @see GossipTopicScoreParams
 */
class GossipTopicsScoreParams(
    private val defaultParams: GossipTopicScoreParams = GossipTopicScoreParams(),
    topicParamsMap: Map<Topic, GossipTopicScoreParams> = mapOf()
) {
    val topicParams: MutableMap<Topic, GossipTopicScoreParams> = topicParamsMap.toMutableMap()

    operator fun get(topic: Topic) = topicParams.getOrDefault(topic, defaultParams)

    fun setTopicParams(topic: Topic, params: GossipTopicScoreParams) {
        topicParams[topic] = params
    }

    fun clearTopicParams(topic: Topic) {
        topicParams.remove(topic)
    }
}

/**
 * Params for specific topic scoring
 */
data class GossipTopicScoreParams(
    /** The weight of the topic. */
    val topicWeight: Weight = 0.0,

    /**
     * P1: time in the mesh
     * This is the time the peer has ben grafted in the mesh.
     * The value of of the parameter is the `time/TimeInMeshQuantum`, capped by [timeInMeshCap]
     * The weight of the parameter MUST be positive (or zero to disable).
     */
    val timeInMeshWeight: Weight = 0.0,
    /** @see timeInMeshWeight */
    val timeInMeshQuantum: Duration = 1.seconds,
    /** @see timeInMeshWeight */
    val timeInMeshCap: Double = 0.0,

    /**
     * P2: first message deliveries
     * This is the number of message deliveries in the topic.
     * The value of the parameter is a counter, decaying with [firstMessageDeliveriesDecay], and capped
     * by [firstMessageDeliveriesCap].
     * The weight of the parameter MUST be positive (or zero to disable).
     */
    val firstMessageDeliveriesWeight: Weight = 0.0,
    /** @see firstMessageDeliveriesWeight */
    val firstMessageDeliveriesDecay: Double = 0.0,
    /** @see firstMessageDeliveriesWeight */
    val firstMessageDeliveriesCap: Double = 0.0,

    /**
     * P3: mesh message deliveries
     * This is the number of message deliveries in the mesh, within the [MeshMessageDeliveriesWindow] of
     * message validation; deliveries during validation also count and are retroactively applied
     * when validation succeeds.
     * This window accounts for the minimum time before a hostile mesh peer trying to game the score
     * could replay back a valid message we just sent them.
     * It effectively tracks first and near-first deliveries, ie a message seen from a mesh peer
     * before we have forwarded it to them.
     * The parameter has an associated counter, decaying with [meshMessageDeliveriesDecay].
     * If the counter exceeds the threshold, its value is 0.
     * If the counter is below the [meshMessageDeliveriesThreshold], the value is the square of
     * the deficit, ie `(messageDeliveriesThreshold - counter)^2`
     * The penalty is only activated after [meshMessageDeliveriesActivation] time in the mesh.
     * The weight of the parameter MUST be negative (or zero to disable).
     */
    val meshMessageDeliveriesWeight: Weight = 0.0 /*-1.0*/, // TODO temporarily exclude this parameter
    /** @see meshMessageDeliveriesWeight */
    val meshMessageDeliveriesDecay: Double = 0.0,
    /** @see meshMessageDeliveriesWeight */
    val meshMessageDeliveriesThreshold: Double = 0.0,
    /** @see meshMessageDeliveriesWeight */
    val meshMessageDeliveriesCap: Double = 0.0,
    /** @see meshMessageDeliveriesWeight */
    val meshMessageDeliveriesActivation: Duration = 1.minutes,
    /** @see meshMessageDeliveriesWeight */
    val meshMessageDeliveryWindow: Duration = 10.millis,

    /**
     * P3b: sticky mesh propagation failures
     * This is a sticky penalty that applies when a peer gets pruned from the mesh with an active
     * mesh message delivery penalty.
     * The weight of the parameter MUST be negative (or zero to disable)
     */
    val meshFailurePenaltyWeight: Weight = 0.0,
    /** @see meshFailurePenaltyWeight */
    val meshFailurePenaltyDecay: Double = 0.0,

    /**
     * P4: invalid messages
     * This is the number of invalid messages in the topic.
     * The value of the parameter is the square of the counter, decaying with
     * [invalidMessageDeliveriesDecay].
     * The weight of the parameter MUST be negative (or zero to disable).
     */
    val invalidMessageDeliveriesWeight: Weight = 0.0,
    /** @see invalidMessageDeliveriesWeight */
    val invalidMessageDeliveriesDecay: Double = 0.0
) {
    init {
        check(timeInMeshWeight >= 0, "timeInMeshWeight >= 0")
        check(timeInMeshCap >= 0, "timeInMeshCap >= 0")
        check(firstMessageDeliveriesWeight >= 0, "firstMessageDeliveriesWeight >= 0")
        check(meshMessageDeliveriesWeight <= 0, "meshMessageDeliveriesWeight <= 0")
        check(meshMessageDeliveriesThreshold >= 0, "meshMessageDeliveriesThreshold >= 0")
        check(
            meshMessageDeliveriesCap >= meshMessageDeliveriesThreshold,
            "meshMessageDeliveriesCap >= meshMessageDeliveriesThreshold"
        )
        check(meshFailurePenaltyWeight <= 0, "meshFailurePenaltyWeight <= 0")
        check(invalidMessageDeliveriesWeight <= 0, "invalidMessageDeliveriesWeight <= 0")
    }
    companion object {
        @JvmStatic
        fun builder() = GossipTopicScoreParamsBuilder(GossipTopicScoreParams())
    }
}

private fun check(condition: Boolean, errMsg: String) {
    if (!condition) throw IllegalArgumentException(errMsg)
}
