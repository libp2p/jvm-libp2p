package io.libp2p.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.InternalErrorException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.etc.MUXER_SESSION
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.TRANSPORT
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
    override val isInitiator: Boolean
) : Connection, P2PChannelOverNetty(ch) {
    override val muxerSession by lazy { ch.attr(MUXER_SESSION).get() }
    override val secureSession by lazy { ch.attr(SECURE_SESSION).get() }
    override val transport by lazy { ch.attr(TRANSPORT).get() }

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