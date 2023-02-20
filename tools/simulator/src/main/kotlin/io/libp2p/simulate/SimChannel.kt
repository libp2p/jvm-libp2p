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
    fun onInbound(message: Any)
    fun onOutbound(message: Any)
}
