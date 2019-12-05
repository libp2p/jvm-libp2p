package io.libp2p.protocol

import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import java.util.concurrent.CompletableFuture

abstract class ProtocolHandler<out TController> : P2PChannelHandler<TController> {
    override fun initChannel(ch: P2PChannel): CompletableFuture<out TController> {
        val stream = ch as Stream
        return if (stream.isInitiator) {
            onStartInitiator(stream)
        } else {
            onStartResponder(stream)
        }
    }

    open fun onStartInitiator(@Suppress("UNUSED_PARAMETER")stream: Stream): CompletableFuture<out TController> {
        throw Libp2pException("This protocol has no initiator")
    }
    open fun onStartResponder(@Suppress("UNUSED_PARAMETER")stream: Stream): CompletableFuture<out TController> {
        throw Libp2pException("This protocol has no responder")
    }
}