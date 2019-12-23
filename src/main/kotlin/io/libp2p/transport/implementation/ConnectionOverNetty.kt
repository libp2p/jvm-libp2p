package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import io.libp2p.etc.CONNECTION
import io.netty.channel.Channel
import java.net.InetSocketAddress

/**
 * A Connection is a high-level wrapper around a Netty Channel representing the conduit to a peer.
 *
 * It exposes libp2p components and semantics via methods and properties.
 */
class ConnectionOverNetty(
    ch: Channel,
    private val transport: Transport,
    override val isInitiator: Boolean
) : Connection, P2PChannelOverNetty(ch) {
    private lateinit var muxerSession: StreamMuxer.Session
    private lateinit var secureSession: SecureChannel.Session

    init {
        ch.attr(CONNECTION).set(this)
    }

    fun setMuxerSession(ms: StreamMuxer.Session) { muxerSession = ms }
    fun setSecureSession(ss: SecureChannel.Session) { secureSession = ss }

    override fun muxerSession() = muxerSession
    override fun secureSession() = secureSession
    override fun transport() = transport

    override fun localAddress(): Multiaddr =
        toMultiaddr(nettyChannel.localAddress() as InetSocketAddress)
    override fun remoteAddress(): Multiaddr =
        toMultiaddr(nettyChannel.remoteAddress() as InetSocketAddress)

    private fun toMultiaddr(addr: InetSocketAddress): Multiaddr {
        if (transport is NettyTransport)
            return transport.toMultiaddr(addr)
        throw RuntimeException("Can not determine address as Multiaddr")
    }
}