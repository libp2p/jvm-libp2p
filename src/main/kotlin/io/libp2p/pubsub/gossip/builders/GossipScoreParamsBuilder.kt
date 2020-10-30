package io.libp2p.pubsub.gossip.builders

import io.libp2p.pubsub.gossip.GossipPeerScoreParams
import io.libp2p.pubsub.gossip.GossipScoreParams
import io.libp2p.pubsub.gossip.GossipTopicsScoreParams

class GossipScoreParamsBuilder() {
    private var peerScoreParams: GossipPeerScoreParams? = null

    private var topicsScoreParams: GossipTopicsScoreParams? = null

    private var gossipThreshold: Double? = null

    private var publishThreshold: Double? = null

    private var graylistThreshold: Double? = null

    private var acceptPXThreshold: Double? = null

    private var opportunisticGraftThreshold: Double? = null

    constructor(source: GossipScoreParams) : this() {
        this.peerScoreParams = source.peerScoreParams
        this.topicsScoreParams = source.topicsScoreParams
        this.gossipThreshold = source.gossipThreshold
        this.publishThreshold = source.publishThreshold
        this.graylistThreshold = source.graylistThreshold
        this.acceptPXThreshold = source.acceptPXThreshold
        this.opportunisticGraftThreshold = source.opportunisticGraftThreshold
    }

    fun peerScoreParams(value: GossipPeerScoreParams): GossipScoreParamsBuilder = apply {
        peerScoreParams = value
    }

    fun topicsScoreParams(value: GossipTopicsScoreParams): GossipScoreParamsBuilder = apply {
        topicsScoreParams = value
    }

    fun gossipThreshold(value: Double): GossipScoreParamsBuilder = apply { gossipThreshold = value }

    fun publishThreshold(value: Double): GossipScoreParamsBuilder = apply { publishThreshold = value }

    fun graylistThreshold(value: Double): GossipScoreParamsBuilder = apply {
        graylistThreshold = value
    }

    fun acceptPXThreshold(value: Double): GossipScoreParamsBuilder = apply {
        acceptPXThreshold = value
    }

    fun opportunisticGraftThreshold(value: Double): GossipScoreParamsBuilder = apply {
        opportunisticGraftThreshold = value
    }

    fun build(): GossipScoreParams {
        checkRequiredFields()
        return GossipScoreParams(
            peerScoreParams = peerScoreParams!!,
            topicsScoreParams = topicsScoreParams!!,
            gossipThreshold = gossipThreshold!!,
            publishThreshold = publishThreshold!!,
            graylistThreshold = graylistThreshold!!,
            acceptPXThreshold = acceptPXThreshold!!,
            opportunisticGraftThreshold = opportunisticGraftThreshold!!
        )
    }

    private fun checkRequiredFields() {
        check(peerScoreParams != null, { "peerScoreParams must not be null" })
        check(topicsScoreParams != null, { "topicsScoreParams must not be null" })
        check(gossipThreshold != null, { "gossipThreshold must not be null" })
        check(publishThreshold != null, { "publishThreshold must not be null" })
        check(graylistThreshold != null, { "graylistThreshold must not be null" })
        check(acceptPXThreshold != null, { "acceptPXThreshold must not be null" })
        check(
            opportunisticGraftThreshold != null,
            { "opportunisticGraftThreshold must not be null" }
        )
    }
}
