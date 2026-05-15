package io.libp2p.pubsub

/**
 * Per-router limits on repeated-field counts inside an inbound pubsub RPC. Enforced
 * at decode time by [RpcMessageCountValidator] to prevent allocation amplification
 * before [pubsub.pb.Rpc.RPC] is materialised.
 *
 * A null field means "no limit" — same semantics as the corresponding nullable
 * fields on `GossipParams`.
 */
data class PubsubRpcLimits(
    val maxPublishedMessages: Int?,
    val maxTopicsPerPublishedMessage: Int?,
    val maxSubscriptions: Int?,
    val maxIHaveMessageIds: Int?,
    val maxIWantMessageIds: Int?,
    val maxGraftMessages: Int?,
    val maxPruneMessages: Int?,
    val maxPeersPerPruneMessage: Int?,
    val maxIDontWantMessages: Int? = null,
    val maxIDontWantMessageIds: Int? = null,
    val rejectEmptyPublishEntries: Boolean = true,
) {
    companion object {
        val NONE = PubsubRpcLimits(
            maxPublishedMessages = null,
            maxTopicsPerPublishedMessage = null,
            maxSubscriptions = null,
            maxIHaveMessageIds = null,
            maxIWantMessageIds = null,
            maxGraftMessages = null,
            maxPruneMessages = null,
            maxPeersPerPruneMessage = null,
            maxIDontWantMessages = null,
            maxIDontWantMessageIds = null,
            rejectEmptyPublishEntries = false,
        )
    }
}
