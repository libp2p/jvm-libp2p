package io.libp2p.simulate

interface SimChannel {

    val stream: SimStream
    val isStreamInitiator: Boolean

    val peer get() =
        when {
            isStreamInitiator -> stream.streamInitiatorPeer
            else -> stream.streamAcceptorPeer
        }

    val msgVisitors: MutableList<SimChannelMessageVisitor>
}

interface SimChannelMessageVisitor {
    fun onOutbound(message: Any)
    fun onInbound(message: Any, delayDetails: DelayDetails)
}

data class DelayDetails(
    val outQueueDelay: Long,
    val outboundBandwidthDelay: Long,
    val latencyDelay: Long,
    val inboundBandwidthDelay: Long,
    val orderingDelay: Long
) {
    private val maybeOrderingString = if (orderingDelay > 0) ("+$orderingDelay") else ""
    private val maybeOutQueueString = if (outQueueDelay > 0) "$outQueueDelay/" else ""
    override fun toString() =
        "${maybeOutQueueString}$latencyDelay+max($outboundBandwidthDelay,$inboundBandwidthDelay)$maybeOrderingString"
}

