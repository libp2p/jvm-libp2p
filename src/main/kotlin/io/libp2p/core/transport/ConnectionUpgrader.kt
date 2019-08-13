package io.libp2p.core.transport

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.mux.StreamMuxer
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
    private val muxers: List<StreamMuxer>
) {

    var beforeSecureHandler: ChannelHandler? = null
    var afterSecureHandler: ChannelHandler? = null

    fun establishSecureChannel(ch: Channel): CompletableFuture<SecureChannel.Session> {
        val (channelHandler, future) =
            Multistream.create(secureChannels).initializer()
        beforeSecureHandler?.also { ch.pipeline().addLast(it) }
        ch.pipeline().addLast(channelHandler)
        afterSecureHandler?.also { ch.pipeline().addLast(it) }
        return future
    }

    fun establishMuxer(ch: Channel, streamHandler: StreamHandler): CompletableFuture<StreamMuxer.Session> {
        val (channelHandler, future) =
            Multistream.create(muxers).initializer()
        future.thenAccept {
            it.streamHandler = streamHandler
        }
        ch.pipeline().addLast(channelHandler)
        return future
    }
}
