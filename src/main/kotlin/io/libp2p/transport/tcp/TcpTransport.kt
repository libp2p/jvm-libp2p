package io.libp2p.transport.tcp

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol.DNSADDR
import io.libp2p.core.multiformats.Protocol.IP4
import io.libp2p.core.multiformats.Protocol.IP6
import io.libp2p.core.multiformats.Protocol.TCP
import io.libp2p.core.multiformats.Protocol.WS
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionBuilder
import io.libp2p.transport.implementation.NettyTransport
import io.netty.channel.ChannelHandler

/**
 * The TCP transport can establish libp2p connections via TCP endpoints.
 *
 * Given that TCP by itself is not authenticated, encrypted, nor multiplexed, this transport uses the upgrader to
 * shim those capabilities via dynamic negotiation.
 */
class TcpTransport(
    upgrader: ConnectionUpgrader
) : NettyTransport(upgrader) {
    // Checks if this transport can handle this multiaddr. It should return true for multiaddrs containing `tcp` atoms.
    override fun handles(addr: Multiaddr): Boolean {
        return (addr.has(IP4) || addr.has(IP6) || addr.has(DNSADDR)) &&
                addr.has(TCP) &&
                !addr.has(WS)
    } // handles

    override fun serverTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? = null

    override fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? = null
} // class TcpTransport