package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.InternalErrorException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import io.libp2p.etc.CONNECTION
import io.netty.channel.Channel
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * A Connection is a high-level wrapper around a Netty Channel representing the conduit to a peer.
 *
 * It exposes libp2p components and semantics via methods and properties.
 */
class ConnectionOverNetty(
    ch: Channel,
    private val transport: Transport,
    initiator: Boolean
) : Connection, P2PChannelOverNetty(ch, initiator) {
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
        else
            return toMultiaddrDefault(addr)
    }

    fun toMultiaddrDefault(addr: InetSocketAddress): Multiaddr {
        val proto = when (addr.address) {
            is Inet4Address -> Protocol.IP4
            is Inet6Address -> Protocol.IP6
            else -> throw InternalErrorException("Unknown address type $addr")
        }
        return Multiaddr(
            listOf(
                proto to proto.addressToBytes(addr.address.hostAddress),
                Protocol.TCP to Protocol.TCP.addressToBytes(addr.port.toString())
            )
        )
    } // toMultiaddr
}
