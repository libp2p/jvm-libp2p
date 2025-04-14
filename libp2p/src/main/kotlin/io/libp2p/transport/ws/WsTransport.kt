package io.libp2p.transport.ws

import io.libp2p.core.multiformats.Multiaddr
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
 * The WS transport can establish libp2p connections
 * via WebSockets endpoints.
 */
class WsTransport(
    upgrader: ConnectionUpgrader
) : PlainNettyTransport(upgrader) {

    override fun handles(addr: Multiaddr) =
        handlesHost(addr) &&
            addr.has(TCP) &&
            addr.has(WS)

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

    override fun toMultiaddr(addr: SocketAddress): Multiaddr =
        MultiaddrUtils.inetSocketAddressToTcpMultiaddr(addr as InetSocketAddress)
            .withComponent(WS)
} // class WsTransport
