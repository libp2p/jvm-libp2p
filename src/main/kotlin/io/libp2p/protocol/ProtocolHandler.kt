package io.libp2p.protocol

import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.P2PChannelHandler
import io.libp2p.core.Stream
import io.libp2p.etc.util.netty.InboundTrafficLimitHandler
import java.util.concurrent.CompletableFuture

abstract class ProtocolHandler<TController>(
    private val initiatorTrafficLimit: Long,
    private val responderTrafficLimit: Long
) : P2PChannelHandler<TController> {

    override fun initChannel(ch: P2PChannel): CompletableFuture<TController> {
        val stream = ch as Stream

        // establish traffic limiter
        val inboundTrafficLimit = if (stream.isInitiator) responderTrafficLimit else initiatorTrafficLimit
        if (inboundTrafficLimit < Long.MAX_VALUE) {
            stream.pushHandler(InboundTrafficLimitHandler(inboundTrafficLimit))
        }

        initProtocolStream(stream)

        return if (stream.isInitiator) {
            onStartInitiator(stream)
        } else {
            onStartResponder(stream)
        }
    }

    protected open fun initProtocolStream(stream: Stream) {}

    protected open fun onStartInitiator(@Suppress("UNUSED_PARAMETER")stream: Stream): CompletableFuture<TController> {
        throw Libp2pException("This protocol has no initiator")
    }
    protected open fun onStartResponder(@Suppress("UNUSED_PARAMETER")stream: Stream): CompletableFuture<TController> {
        throw Libp2pException("This protocol has no responder")
    }
}
