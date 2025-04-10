package io.libp2p.transport.tcp

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol.DNSADDR
import io.libp2p.core.multiformats.Protocol.P2PCIRCUIT
import io.libp2p.core.multiformats.Protocol.TCP
import io.libp2p.core.multiformats.Protocol.WS
import io.libp2p.etc.util.MultiaddrUtils
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionBuilder
import io.libp2p.transport.implementation.PlainNettyTransport
import io.netty.channel.ChannelHandler
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * The TCP transport can establish libp2p connections via TCP endpoints.
 *
 * Given that TCP by itself is not authenticated, encrypted, nor multiplexed, this transport uses the upgrader to
 * shim those capabilities via dynamic negotiation.
 */
open class TcpTransport(
    upgrader: ConnectionUpgrader
) : PlainNettyTransport(upgrader) {

    override fun handles(addr: Multiaddr) =
        handlesHost(addr) &&
            addr.has(TCP) &&
            !addr.has(WS) &&
            !addr.has(DNSADDR) &&
            !addr.has(P2PCIRCUIT)

    override fun serverTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? = null

    override fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? = null

    override fun toMultiaddr(addr: SocketAddress): Multiaddr =
        MultiaddrUtils.inetSocketAddressToTcpMultiaddr(addr as InetSocketAddress)
} // class TcpTransport
