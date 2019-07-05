package io.libp2p.core.transport

import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture

/**
 * ConnectionUpgrader is a utility class that Transports can use to shim secure channels and muxers when those
 * capabilities are not provided natively by the transport.
 */
class ConnectionUpgrader(
    private val secureChannels: List<SecureChannel>,
    private val muxers: List<StreamMuxer>
) {
    fun establishSecureChannel(ch: Channel): ChannelFuture = TODO()

    fun establishMuxer(ch: Channel): ChannelFuture = TODO()
}
