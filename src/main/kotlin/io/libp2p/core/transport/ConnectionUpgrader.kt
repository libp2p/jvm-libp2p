package io.libp2p.core.transport

import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.protocol.Multistream
import io.libp2p.core.security.SecureChannel
import io.netty.channel.Channel
import java.util.concurrent.CompletableFuture

/**
 * ConnectionUpgrader is a utility class that Transports can use to shim secure channels and muxers when those
 * capabilities are not provided natively by the transport.
 */
class ConnectionUpgrader(
    private val secureChannels: List<SecureChannel>,
    private val muxers: List<StreamMuxer>
) {
    fun establishSecureChannel(ch: Channel, initiator: Boolean): CompletableFuture<SecureChannel.Session> {
        val (channelHandler, future) =
            Multistream.create(secureChannels, initiator).initializer()
        ch.pipeline().addLast(channelHandler)
        return future
    }

    fun establishMuxer(ch: Channel, initiator: Boolean): CompletableFuture<StreamMuxer.Session> {
        val (channelHandler, future) =
            Multistream.create(muxers, initiator).initializer()
        ch.pipeline().addLast(channelHandler)
        return future
    }
}
