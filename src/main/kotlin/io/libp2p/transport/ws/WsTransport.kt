package io.libp2p.transport.ws

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.REMOTE_PEER_ID
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.ConnectionOverNetty
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

class WsTransport(
    val upgrader: ConnectionUpgrader
) : Transport {
    private var closed = false
    val activeListeners = mutableMapOf<Multiaddr, Channel>()

    // server Bootstrap prototype
    private var workerGroup by lazyVar { NioEventLoopGroup() }
    private var bossGroup by lazyVar { workerGroup }

    private var server by lazyVar {
        ServerBootstrap().apply {
            group(bossGroup, workerGroup)
            channel(NioServerSocketChannel::class.java)
        }
    }

    override fun initialize() {
    }

    override fun handles(addr: Multiaddr): Boolean {
        return (addr.has(Protocol.IP4) || addr.has(Protocol.IP6) || addr.has(Protocol.DNSADDR)) &&
                addr.has(Protocol.TCP) &&
                addr.has(Protocol.WS)
    }

    override fun close(): CompletableFuture<Unit> {
        closed = true

        val unbindsCompleted = activeListeners
            .map { (_, ch) -> ch }
            .map { it.close().toVoidCompletableFuture() }

        return CompletableFuture.allOf(*unbindsCompleted.toTypedArray()).thenApply { }
    } // close

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        val listener = server.clone()
            .childHandler(WebSocketServerInitializer())

        val bindComplete = listener.bind(fromMultiaddr(addr))

        bindComplete.also {
            activeListeners += addr to it.channel()
            it.channel().closeFuture().addListener {
                synchronized(this@WsTransport) {
                    activeListeners -= addr
                }
            }
        }

        return bindComplete.toVoidCompletableFuture()
    } // listener

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return activeListeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    } // unlisten

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        TODO("not implemented")
    }

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = addr.filterStringComponents().find { p -> p.first in arrayOf(
            Protocol.IP4,
            Protocol.IP6,
            Protocol.DNSADDR
        ) }
            ?.second ?: throw Libp2pException("Missing IP4/IP6/DNSADDR in multiaddress $addr")
        val port = addr.filterStringComponents().find { p -> p.first == Protocol.TCP }
            ?.second ?: throw Libp2pException("Missing TCP in multiaddress $addr")
        return InetSocketAddress(host, port.toInt())
    }
}