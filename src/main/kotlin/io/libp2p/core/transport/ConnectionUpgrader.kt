package io.libp2p.core.transport

import io.libp2p.core.StreamHandler
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.protocol.Multistream
import io.libp2p.core.security.SecureChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

/**
 * ConnectionUpgrader is a utility class that Transports can use to shim secure channels and muxers when those
 * capabilities are not provided natively by the transport.
 */
class ConnectionUpgrader(
    private val secureChannels: List<SecureChannel>,
    private val muxers: List<StreamMuxer>,
    private val beforeSecureHandler: ChannelHandler? = null,
    private val afterSecureHandler: ChannelHandler? = null
) {
    fun establishSecureChannel(ch: Channel, initiator: Boolean): CompletableFuture<SecureChannel.Session> {
        val (channelHandler, future) =
            Multistream.create(secureChannels, initiator).initializer()
        if (beforeSecureHandler != null) {
            ch.pipeline().addLast(beforeSecureHandler)
            future.thenAccept { ch.pipeline().remove(beforeSecureHandler) }
        }
        ch.pipeline().addLast(channelHandler)
        if (afterSecureHandler != null) {
            ch.pipeline().addLast(afterSecureHandler)
        }
        return future
    }

    fun establishMuxer(ch: Channel, streamHandler: StreamHandler, isInitiator: Boolean): CompletableFuture<StreamMuxer.Session> {
        val (channelHandler, future) =
            Multistream.create(muxers, isInitiator).initializer()
        future.thenAccept {
            it.streamHandler = streamHandler
        }
        ch.pipeline().addLast(channelHandler)
        return future
    }
}
