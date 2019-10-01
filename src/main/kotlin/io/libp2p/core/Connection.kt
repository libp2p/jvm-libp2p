package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import io.libp2p.etc.MUXER_SESSION
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.TRANSPORT
import io.netty.channel.Channel
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

interface Connection : P2PChannel {
    /**
     * Returns the [io.libp2p.core.mux.StreamMuxer.Session] which is capable of creating
     * new [Stream]s
     */
    val muxerSession: StreamMuxer.Session

    /**
     * Returns the [io.libp2p.core.security.SecureChannel.Session] which contains
     * security attributes of this connection
     */
    val secureSession: SecureChannel.Session

    /**
     * Returns the [io.libp2p.core.transport.Transport] instance behind this [Connection]
     */
    val transport: Transport

    /**
     * Returns the local [Multiaddr] of this [Connection]
     */
    fun localAddress(): Multiaddr
    /**
     * Returns the remote [Multiaddr] of this [Connection]
     */
    fun remoteAddress(): Multiaddr
}

/**
 * A Connection is a high-level wrapper around a Netty Channel representing the conduit to a peer.
 *
 * It exposes libp2p components and semantics via methods and properties.
 */
class ConnectionOverNetty(ch: Channel) : Connection, P2PAbstractChannel(ch) {
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
