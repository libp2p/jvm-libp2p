package io.libp2p.protocol

import io.libp2p.core.Stream

interface ProtocolMessageHandler<TMessage> {
    fun onActivated(stream: Stream) = Unit
    fun onMessage(stream: Stream, msg: TMessage) = Unit
    fun onClosed(stream: Stream) = Unit
    fun onException(stream: Stream) = Unit
}
