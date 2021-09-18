package io.libp2p.transport.tcp

import io.libp2p.core.InternalErrorException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol.IP4
import io.libp2p.core.multiformats.Protocol.IP6
import io.libp2p.core.multiformats.Protocol.TCP
import io.libp2p.core.multiformats.Protocol.WS
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionBuilder
import io.libp2p.transport.implementation.NettyTransport
import io.netty.channel.ChannelHandler
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

/**
 * The TCP transport can establish libp2p connections via TCP endpoints.
 *
 * Given that TCP by itself is not authenticated, encrypted, nor multiplexed, this transport uses the upgrader to
 * shim those capabilities via dynamic negotiation.
 */
class TcpTransport(
    upgrader: ConnectionUpgrader
) : NettyTransport(upgrader) {

    override fun handles(addr: Multiaddr) =
        handlesHost(addr) &&
            addr.has(TCP) &&
            !addr.has(WS)

    override fun serverTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? = null

    override fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? = null

    override fun toMultiaddr(addr: InetSocketAddress): Multiaddr {
        val proto = when (addr.address) {
            is Inet4Address -> IP4
            is Inet6Address -> IP6
            else -> throw InternalErrorException("Unknown address type $addr")
        }
        return Multiaddr(
            listOf(
                proto to proto.addressToBytes(addr.address.hostAddress),
                TCP to TCP.addressToBytes(addr.port.toString())
            )
        )
    } // toMultiaddr
} // class TcpTransport
