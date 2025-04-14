package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.CONNECTION
import io.netty.channel.Channel

/**
 * A Connection is a high-level wrapper around a Netty Channel representing the conduit to a peer.
 *
 * It exposes libp2p components and semantics via methods and properties.
 */
open class ConnectionOverNetty(
    ch: Channel,
    private val nettyTransport: NettyTransport,
    initiator: Boolean
) : Connection, P2PChannelOverNetty(ch, initiator) {
    private lateinit var muxerSession: StreamMuxer.Session
    private lateinit var secureSession: SecureChannel.Session

    init {
        ch.attr(CONNECTION).set(this)
    }

    fun setMuxerSession(ms: StreamMuxer.Session) {
        muxerSession = ms
    }
    fun setSecureSession(ss: SecureChannel.Session) {
        secureSession = ss
    }

    override fun muxerSession() = muxerSession
    override fun secureSession() = secureSession
    override fun transport() = nettyTransport

    override fun localAddress(): Multiaddr = nettyTransport.localAddress(nettyChannel)
    override fun remoteAddress(): Multiaddr = nettyTransport.remoteAddress(nettyChannel)
}
