package io.libp2p.transport.ws

import io.libp2p.core.InternalErrorException
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.implementation.ConnectionBuilder
import io.libp2p.transport.implementation.NettyTransport
import io.netty.channel.ChannelHandler
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

class WsTransport(
    upgrader: ConnectionUpgrader
) : NettyTransport(upgrader) {
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

    override fun toMultiaddr(addr: InetSocketAddress): Multiaddr {
        val proto = when (addr.address) {
            is Inet4Address -> Protocol.IP4
            is Inet6Address -> Protocol.IP6
            else -> throw InternalErrorException("Unknown address type $addr")
        }
        return Multiaddr(listOf(
            proto to proto.addressToBytes(addr.address.hostAddress),
            Protocol.TCP to Protocol.TCP.addressToBytes(addr.port.toString()),
            Protocol.WS to ByteArray(0)
        ))
    } // toMultiaddr
} // class WsTransport