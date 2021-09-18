package io.libp2p.transport.ws

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
 * The WS transport can establish libp2p connections
 * via WebSockets endpoints.
 */
class WsTransport(
    upgrader: ConnectionUpgrader
) : NettyTransport(upgrader) {

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

    override fun toMultiaddr(addr: InetSocketAddress): Multiaddr {
        val proto = when (addr.address) {
            is Inet4Address -> IP4
            is Inet6Address -> IP6
            else -> throw InternalErrorException("Unknown address type $addr")
        }
        return Multiaddr(
            listOf(
                proto to proto.addressToBytes(addr.address.hostAddress),
                TCP to TCP.addressToBytes(addr.port.toString()),
                WS to ByteArray(0)
            )
        )
    } // toMultiaddr
} // class WsTransport
