package io.libp2p.pubsub.gossip.builders

import io.libp2p.core.PeerId
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.defaultDHigh
import io.libp2p.pubsub.gossip.defaultDLazy
import io.libp2p.pubsub.gossip.defaultDLow
import io.libp2p.pubsub.gossip.defaultDOut
import io.libp2p.pubsub.gossip.defaultDScore
import java.time.Duration

class GossipParamsBuilder {
    private var D: Int? = null

    private var DLow: Int? = null

    private var DHigh: Int? = null

    private var DScore: Int? = null

    private var DOut: Int? = null

    private var DLazy: Int? = null

    private var fanoutTTL: Duration? = null

    private var maxGossipMessageSize: Int? = null

    private var gossipSize: Int? = null

    private var gossipHistoryLength: Int? = null

    private var heartbeatInterval: Duration? = null

    private var seenTTL: Duration? = null

    private var maxPrunePeers: Int? = null

    private var maxPeersPerPruneMessage: Int? = null

    private var pruneBackoff: Duration? = null

    private var floodPublish: Boolean? = null

    private var gossipFactor: Double? = null

    private var opportunisticGraftPeers: Int? = null

    private var opportunisticGraftTicks: Int? = null

    private var graftFloodThreshold: Duration? = null

    private var maxPublishedMessages: Int? = null

    private var maxTopicsPerPublishedMessage: Int? = null

    private var maxSubscriptions: Int? = null

    private var maxIHaveLength: Int? = null

    private var maxIHaveMessages: Int? = null

    private var maxIWantMessageIds: Int? = null

    private var iWantFollowupTime: Duration? = null

    private var maxGraftMessages: Int? = null

    private var maxPruneMessages: Int? = null

    private var gossipRetransmission: Int? = null

    private var connectCallback: Function2<PeerId, ByteArray, Unit>? = null

    init {
        val source = GossipParams()
        this.D = source.D
        this.fanoutTTL = source.fanoutTTL
        this.maxGossipMessageSize = source.maxGossipMessageSize
        this.gossipSize = source.gossipSize
        this.gossipHistoryLength = source.gossipHistoryLength
        this.heartbeatInterval = source.heartbeatInterval
        this.seenTTL = source.seenTTL
        this.maxPrunePeers = source.maxPrunePeers
        this.maxPeersPerPruneMessage = source.maxPeersPerPruneMessage
        this.pruneBackoff = source.pruneBackoff
        this.floodPublish = source.floodPublish
        this.gossipFactor = source.gossipFactor
        this.opportunisticGraftPeers = source.opportunisticGraftPeers
        this.opportunisticGraftTicks = source.opportunisticGraftTicks
        this.graftFloodThreshold = source.graftFloodThreshold
        this.maxPublishedMessages = source.maxPublishedMessages
        this.maxTopicsPerPublishedMessage = source.maxTopicsPerPublishedMessage
        this.maxSubscriptions = source.maxSubscriptions
        this.maxIHaveLength = source.maxIHaveLength
        this.maxIHaveMessages = source.maxIHaveMessages
        this.maxIWantMessageIds = source.maxIWantMessageIds
        this.iWantFollowupTime = source.iWantFollowupTime
        this.maxGraftMessages = source.maxGraftMessages
        this.maxPruneMessages = source.maxPruneMessages
        this.gossipRetransmission = source.gossipRetransmission
        this.connectCallback = source.connectCallback
    }

    fun D(value: Int): GossipParamsBuilder = apply { D = value }

    fun DLow(value: Int): GossipParamsBuilder = apply { DLow = value }

    fun DHigh(value: Int): GossipParamsBuilder = apply { DHigh = value }

    fun DScore(value: Int): GossipParamsBuilder = apply { DScore = value }

    fun DOut(value: Int): GossipParamsBuilder = apply { DOut = value }

    fun DLazy(value: Int): GossipParamsBuilder = apply { DLazy = value }

    fun fanoutTTL(value: Duration): GossipParamsBuilder = apply { fanoutTTL = value }

    fun maxGossipMessageSize(value: Int): GossipParamsBuilder = apply { maxGossipMessageSize = value }

    fun gossipSize(value: Int): GossipParamsBuilder = apply { gossipSize = value }

    fun gossipHistoryLength(value: Int): GossipParamsBuilder = apply { gossipHistoryLength = value }

    fun heartbeatInterval(value: Duration): GossipParamsBuilder = apply { heartbeatInterval = value }

    fun seenTTL(value: Duration): GossipParamsBuilder = apply { seenTTL = value }

    fun maxPrunePeers(value: Int): GossipParamsBuilder = apply { maxPrunePeers = value }

    fun maxPeersPerPruneMessage(value: Int): GossipParamsBuilder = apply { maxPeersPerPruneMessage = value }

    fun pruneBackoff(value: Duration): GossipParamsBuilder = apply { pruneBackoff = value }

    fun floodPublish(value: Boolean): GossipParamsBuilder = apply { floodPublish = value }

    fun gossipFactor(value: Double): GossipParamsBuilder = apply { gossipFactor = value }

    fun opportunisticGraftPeers(value: Int): GossipParamsBuilder = apply {
        opportunisticGraftPeers = value
    }

    fun opportunisticGraftTicks(value: Int): GossipParamsBuilder = apply {
        opportunisticGraftTicks = value
    }

    fun graftFloodThreshold(value: Duration): GossipParamsBuilder = apply {
        graftFloodThreshold = value
    }

    fun maxPublishedMessages(value: Int): GossipParamsBuilder = apply { maxPublishedMessages = value }

    fun maxTopicsPerPublishedMessage(value: Int): GossipParamsBuilder = apply { maxTopicsPerPublishedMessage = value }

    fun maxSubscriptions(value: Int): GossipParamsBuilder = apply { maxSubscriptions = value }

    fun maxIHaveLength(value: Int): GossipParamsBuilder = apply { maxIHaveLength = value }

    fun maxIHaveMessages(value: Int): GossipParamsBuilder = apply { maxIHaveMessages = value }

    fun maxIWantMessageIds(value: Int): GossipParamsBuilder = apply { maxIWantMessageIds = value }

    fun iWantFollowupTime(value: Duration): GossipParamsBuilder = apply { iWantFollowupTime = value }

    fun maxGraftMessages(value: Int): GossipParamsBuilder = apply { maxGraftMessages = value }

    fun maxPruneMessages(value: Int): GossipParamsBuilder = apply { maxPruneMessages = value }

    fun gossipRetransmission(value: Int): GossipParamsBuilder = apply { gossipRetransmission = value }

    fun connectCallback(value: Function2<PeerId, ByteArray, Unit>): GossipParamsBuilder = apply {
        connectCallback = value
    }

    fun build(): GossipParams {
        calculateMissing()
        checkRequiredFields()
        return GossipParams(
            D = D!!,
            DLow = DLow!!,
            DHigh = DHigh!!,
            DScore = DScore!!,
            DOut = DOut!!,
            DLazy = DLazy!!,
            fanoutTTL = fanoutTTL!!,
            maxGossipMessageSize = maxGossipMessageSize!!,
            gossipSize = gossipSize!!,
            gossipHistoryLength = gossipHistoryLength!!,
            heartbeatInterval = heartbeatInterval!!,
            seenTTL = seenTTL!!,
            floodPublish = floodPublish!!,
            gossipFactor = gossipFactor!!,
            opportunisticGraftPeers = opportunisticGraftPeers!!,
            opportunisticGraftTicks = opportunisticGraftTicks!!,
            graftFloodThreshold = graftFloodThreshold!!,
            maxPublishedMessages = maxPublishedMessages,
            maxTopicsPerPublishedMessage = maxTopicsPerPublishedMessage,
            maxSubscriptions = maxSubscriptions,
            maxIHaveLength = maxIHaveLength!!,
            maxIHaveMessages = maxIHaveMessages!!,
            maxIWantMessageIds = maxIWantMessageIds,
            iWantFollowupTime = iWantFollowupTime!!,
            maxGraftMessages = maxGraftMessages,
            maxPrunePeers = maxPrunePeers!!,
            maxPeersPerPruneMessage = maxPeersPerPruneMessage,
            pruneBackoff = pruneBackoff!!,
            maxPruneMessages = maxPruneMessages,
            gossipRetransmission = gossipRetransmission!!,
            connectCallback = connectCallback!!
        )
    }

    private fun calculateMissing() {
        check(D != null, { "D must not be null" })
        DLow = DLow ?: defaultDLow(D!!)
        DHigh = DHigh ?: defaultDHigh(D!!)
        DScore = DScore ?: defaultDScore(D!!)
        DOut = DOut ?: defaultDOut(D!!, DLow!!)
        DLazy = DLazy ?: defaultDLazy(D!!)
    }

    private fun checkRequiredFields() {
        check(D != null, { "D must not be null" })
        check(DLow != null, { "DLow must not be null" })
        check(DHigh != null, { "DHigh must not be null" })
        check(DScore != null, { "DScore must not be null" })
        check(DOut != null, { "DOut must not be null" })
        check(DLazy != null, { "DLazy must not be null" })
        check(fanoutTTL != null, { "fanoutTTL must not be null" })
        check(maxGossipMessageSize != null, { "maxGossipMessageSize must not be null" })
        check(gossipSize != null, { "gossipSize must not be null" })
        check(gossipHistoryLength != null, { "gossipHistoryLength must not be null" })
        check(heartbeatInterval != null, { "heartbeatInterval must not be null" })
        check(seenTTL != null, { "seenTTL must not be null" })
        check(maxPrunePeers != null, { "maxPrunePeers must not be null" })
        check(pruneBackoff != null, { "pruneBackoff must not be null" })
        check(floodPublish != null, { "floodPublish must not be null" })
        check(gossipFactor != null, { "gossipFactor must not be null" })
        check(opportunisticGraftPeers != null, { "opportunisticGraftPeers must not be null" })
        check(opportunisticGraftTicks != null, { "opportunisticGraftTicks must not be null" })
        check(graftFloodThreshold != null, { "graftFloodThreshold must not be null" })
        check(maxIHaveLength != null, { "maxIHaveLength must not be null" })
        check(maxIHaveMessages != null, { "maxIHaveMessages must not be null" })
        check(iWantFollowupTime != null, { "iWantFollowupTime must not be null" })
        check(gossipRetransmission != null, { "gossipRetransmission must not be null" })
        check(connectCallback != null, { "connectCallback must not be null" })
    }
}
