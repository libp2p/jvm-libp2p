package io.libp2p.pubsub

/**
 * Per-router limits on repeated-field counts inside an inbound pubsub RPC. Enforced
 * at decode time by [RpcMessageCountValidator] to prevent allocation amplification
 * before [pubsub.pb.Rpc.RPC] is materialised.
 *
 * A null field means "no limit" — same semantics as the corresponding nullable
 * fields on `GossipParams`.
 *
 * Exceeding any of these drops the frame without penalising the peer, and that is deliberate.
 * These are local policy, not protocol rules: the gossipsub spec sets no numeric limits, go-libp2p
 * bounds control bytes only, and rust-libp2p bounds control bytes plus a publish-entry count. A
 * peer configured more loosely than us is therefore conformant, not hostile, and a frame that trips
 * a limit here carries no proof of malice. Scoring on it would penalise honest peers whose limits
 * happen to differ, so [RpcCountFrameDecoder] logs and drops instead. Only genuinely malformed
 * bytes take the behaviour-penalty path.
 */
data class PubsubRpcLimits(
    val maxPublishedMessages: Int?,
    val maxTopicsPerPublishedMessage: Int?,
    val rejectEmptyPublishEntries: Boolean = true,
    /**
     * Cumulative wire size of an inbound RPC's control plane: everything except
     * `publish` payloads and `RPC.partial`. Bounds allocation shape-agnostically,
     * since every protobuf envelope costs at least two wire bytes.
     */
    val maxControlMessageSize: Int? = null,
    /**
     * Total number of protobuf fields an inbound RPC may contain, summed across every nesting
     * level the validator walks. Bounds the number of objects protobuf-java materialises, which
     * [maxControlMessageSize] only bounds indirectly: the cheapest object - an empty repeated
     * sub-message, or a retained unknown field - costs two wire bytes, so a byte budget alone
     * permits `maxControlMessageSize / 2` allocations.
     */
    val maxTotalFields: Int? = null,
) {
    /**
     * True when no configured limit or reject-flag can fire. Lets
     * [RpcCountFrameDecoder] skip the validator walk entirely on the toggle-off
     * path. Any new field added to this data class must be considered here.
     */
    val isNoop: Boolean =
        maxPublishedMessages == null &&
            maxTopicsPerPublishedMessage == null &&
            !rejectEmptyPublishEntries &&
            maxControlMessageSize == null &&
            maxTotalFields == null

    companion object {
        val NONE = PubsubRpcLimits(
            maxPublishedMessages = null,
            maxTopicsPerPublishedMessage = null,
            rejectEmptyPublishEntries = false,
            maxControlMessageSize = null,
            maxTotalFields = null,
        )
    }
}
