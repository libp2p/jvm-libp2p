package io.libp2p.core.security

import io.libp2p.core.protocol.ProtocolMatcher
import io.netty.channel.ChannelInitializer
import io.netty.channel.socket.SocketChannel

/**
 * The SecureChannel interface is implemented by all security channels, such as SecIO, TLS 1.3, Noise, and so on.
 */
interface SecureChannel {
    /**
     * The criteria that will be evaluated by protocol negotiators to determine whether
     * to activate this secure channel.
     */
    val matcher: ProtocolMatcher

    /**
     * Returns the ChannelInitializer that will be invoked to initialize the channel when
     * this secure channel activates.
     */
    fun initializer(): ChannelInitializer<SocketChannel>
}