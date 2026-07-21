package io.libp2p.pubsub

import pubsub.pb.Rpc

private const val MIB = 1024L * 1024L
const val DEFAULT_MAX_OUTBOUND_RETAINED_ENTRIES_PER_PEER = 10_000

data class PubsubOutboundLimits(
    val maxRetainedBytesPerPeer: Long,
    val maxRetainedEntriesPerPeer: Int
) {
    fun validate(maxMessageSize: Int): PubsubOutboundLimits = apply {
        require(maxRetainedBytesPerPeer >= maxMessageSize.toLong()) {
            "maxOutboundRetainedBytesPerPeer ($maxRetainedBytesPerPeer) must be >= maxGossipMessageSize ($maxMessageSize)"
        }
        require(maxRetainedEntriesPerPeer >= 1) {
            "maxOutboundRetainedEntriesPerPeer must be >= 1: $maxRetainedEntriesPerPeer"
        }
    }

    companion object {
        fun defaults(maxMessageSize: Int): PubsubOutboundLimits =
            PubsubOutboundLimits(
                maxOf(8L * MIB, maxMessageSize.toLong() + 4L * MIB),
                DEFAULT_MAX_OUTBOUND_RETAINED_ENTRIES_PER_PEER
            )

        val UNBOUNDED = PubsubOutboundLimits(Long.MAX_VALUE, Int.MAX_VALUE)
    }
}

data class OutboundResourceUsage internal constructor(
    val bytes: Long = 0,
    val entries: Long = 0
) {
    fun plusOrNull(other: OutboundResourceUsage): OutboundResourceUsage? {
        if (other.bytes < 0 || other.entries < 0) return null
        if (Long.MAX_VALUE - bytes < other.bytes) return null
        if (Long.MAX_VALUE - entries < other.entries) return null
        return OutboundResourceUsage(bytes + other.bytes, entries + other.entries)
    }

    fun fits(limits: PubsubOutboundLimits): Boolean =
        bytes <= limits.maxRetainedBytesPerPeer &&
            entries <= limits.maxRetainedEntriesPerPeer.toLong()

    companion object {
        val ZERO = OutboundResourceUsage()
    }
}

internal fun OutboundResourceUsage.Companion.fromRpc(
    rpc: Rpc.RPC,
    promiseEntries: Long = 0
): OutboundResourceUsage {
    val controlEntries = if (rpc.hasControl()) {
        val control = rpc.control
        control.ihaveCount.toLong() +
            control.ihaveList.sumOf { it.messageIDsCount.toLong() } +
            control.iwantCount.toLong() +
            control.iwantList.sumOf { it.messageIDsCount.toLong() } +
            control.graftCount.toLong() +
            control.pruneCount.toLong() +
            control.pruneList.sumOf { it.peersCount.toLong() } +
            control.idontwantCount.toLong() +
            control.idontwantList.sumOf { it.messageIDsCount.toLong() } +
            (if (control.hasExtensions()) 1L else 0L)
    } else {
        0L
    }
    val logicalEntries =
        1L +
            rpc.subscriptionsCount.toLong() +
            rpc.publishList.sumOf { 1L + it.topicIDsCount.toLong() } +
            controlEntries +
            promiseEntries +
            (if (rpc.hasPartial()) 1L else 0L) +
            (if (rpc.hasTestExtension()) 1L else 0L)
    return OutboundResourceUsage(rpc.serializedSize.toLong(), logicalEntries)
}
