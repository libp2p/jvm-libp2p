package io.libp2p.transport.ws

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.transport.ConnectionUpgrader
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

    override fun serverInitializer(addr: Multiaddr): ChannelHandler? {
        return WebSocketServerInitializer()
    } // serverInitializer

    override fun clientInitializer(addr: Multiaddr): ChannelHandler? {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        val url = "ws://$host:$port/"

        return WebSocketClientInitializer(url)
    } // clientInitializer
} // class WsTransport