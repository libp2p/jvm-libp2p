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

    // Addresses are resolved from the underlying Netty channel, but some transports (e.g. QUIC)
    // free the native channel state on close, after which the socket addresses are no longer
    // available. We cache the addresses on first successful read while the channel is still live
    // so that teardown-time callers (e.g. disconnect handlers) get a stable value instead of an NPE.
    @Volatile
    private var cachedLocalAddress: Multiaddr? = null

    @Volatile
    private var cachedRemoteAddress: Multiaddr? = null

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

    override fun localAddress(): Multiaddr =
        cachedLocalAddress ?: nettyTransport.localAddress(nettyChannel).also { cachedLocalAddress = it }

    override fun remoteAddress(): Multiaddr =
        cachedRemoteAddress ?: nettyTransport.remoteAddress(nettyChannel).also { cachedRemoteAddress = it }
}
