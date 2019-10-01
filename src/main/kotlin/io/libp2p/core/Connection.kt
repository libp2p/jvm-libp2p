package io.libp2p.core

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
class Connection(ch: Channel) : P2PAbstractChannel(ch), P2PChannel {
    /**
     * Returns the [io.libp2p.core.mux.StreamMuxer.Session] which is capable of creating
     * new [Stream]s
     */
    val muxerSession by lazy { ch.attr(MUXER_SESSION).get() }
    /**
     * Returns the [io.libp2p.core.security.SecureChannel.Session] which contains
     * security attributes of this connection
     */
    val secureSession by lazy { ch.attr(SECURE_SESSION).get() }

    /**
     * Returns the [io.libp2p.core.transport.Transport] instance behind this [Connection]
     */
    val transport by lazy { ch.attr(TRANSPORT).get() }

    /**
     * Returns the remote [Multiaddr] of this [Connection]
     */
    fun remoteAddress(): Multiaddr =
        toMultiaddr(nettyChannel.remoteAddress() as InetSocketAddress)

    /**
     * Returns the local [Multiaddr] of this [Connection]
     */
    fun localAddress(): Multiaddr =
        toMultiaddr(nettyChannel.localAddress() as InetSocketAddress)

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
