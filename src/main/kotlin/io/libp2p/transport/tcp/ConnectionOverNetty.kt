package io.libp2p.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.InternalErrorException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.transport.Transport
import io.libp2p.etc.SECURE_SESSION
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
    override val isInitiator: Boolean
) : Connection, P2PChannelOverNetty(ch) {
    private lateinit var muxerSession: StreamMuxer.Session
    private val secureSession by lazy { ch.attr(SECURE_SESSION).get() }

    fun setMuxer(ms: StreamMuxer.Session) { muxerSession = ms }

    override fun muxerSession() = muxerSession
    override fun secureSession() = secureSession
    override fun transport() = transport

    override fun localAddress(): Multiaddr =
        toMultiaddr(nettyChannel.localAddress() as InetSocketAddress)
    override fun remoteAddress(): Multiaddr =
        toMultiaddr(nettyChannel.remoteAddress() as InetSocketAddress)

    private fun toMultiaddr(addr: InetSocketAddress): Multiaddr {
        val proto = when (addr.address) {
            is Inet4Address -> Protocol.IP4
            is Inet6Address -> Protocol.IP6
            else -> throw InternalErrorException("Unknown address type $addr")
        }
        return Multiaddr(listOf(
            proto to proto.addressToBytes(addr.address.hostAddress),
            Protocol.TCP to Protocol.TCP.addressToBytes(addr.port.toString())
        ))
    }
}