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

data class DelayData(
    val wireDelay: Long,
    val outboundBandwidthDelay: Long,
    val latencyDelay: Long,
    val inboundBandwidthDelay: Long
)

interface SimChannelMessageVisitor {
    fun onOutbound(message: Any)
    fun onInbound(message: Any, delayData: DelayData)
}
