package io.libp2p.transport.ws

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionBuilder
import io.libp2p.transport.implementation.NettyTransportBase
import io.netty.channel.ChannelHandler

class WsTransport(
    upgrader: ConnectionUpgrader
) : NettyTransportBase(upgrader) {
    override fun handles(addr: Multiaddr): Boolean {
        return (addr.has(Protocol.IP4) || addr.has(Protocol.IP6) || addr.has(Protocol.DNSADDR)) &&
                addr.has(Protocol.TCP) &&
                addr.has(Protocol.WS)
    } // handles

    override fun serverTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? {
        return WebSocketServerInitializer(connectionBuilder)
    } // serverTransportBuilder

    override fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler? {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        val url = "ws://$host:$port/"

        return WebSocketClientInitializer(connectionBuilder, url)
    } // clientTransportBuilder
} // class WsTransport