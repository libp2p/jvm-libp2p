package io.libp2p.core.transport.tcp

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.StreamHandler
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol.DNSADDR
import io.libp2p.core.multiformats.Protocol.IP4
import io.libp2p.core.multiformats.Protocol.IP6
import io.libp2p.core.multiformats.Protocol.TCP
import io.libp2p.core.transport.AbstractTransport
import io.libp2p.core.transport.ConnectionUpgrader
import io.libp2p.core.types.lazyVar
import io.libp2p.core.types.toCompletableFuture
import io.libp2p.core.types.toVoidCompletableFuture
import io.libp2p.core.util.netty.nettyInitializer
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * The TCP transport can establish libp2p connections via TCP endpoints.
 *
 * Given that TCP by itself is not authenticated, encrypted, nor multiplexed, this transport uses the upgrader to
 * shim those capabilities via dynamic negotiation.
 */
class TcpTransport(
    upgrader: ConnectionUpgrader
) : AbstractTransport(upgrader) {

    var workerGroup by lazyVar { NioEventLoopGroup() }
    var bossGroup by lazyVar { workerGroup }
    var connectTimeout = Duration.ofSeconds(15)

    val activeListeners = mutableMapOf<Multiaddr, Channel>()
    val activeChannels = mutableListOf<Channel>()
    var closed = false

    // client Bootstrap prototype
    var client by lazyVar {
        Bootstrap().apply {
            group(workerGroup)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
        }
    }

    // server Bootstrap prototype
    var server by lazyVar {
        ServerBootstrap().apply {
            group(bossGroup, workerGroup)
            channel(NioServerSocketChannel::class.java)
        }
    }

    // Initializes the server and client fields, preparing them to establish outbound connections (client)
    // and to accept inbound connections (server).
    override fun initialize() {
    }

    // Checks if this transport can handle this multiaddr. It should return true for multiaddrs containing `tcp` atoms.
    override fun handles(addr: Multiaddr): Boolean {
        return addr.components
            .any { pair -> pair.first == TCP }
    }

    // Closes this transport entirely, aborting all ongoing connections and shutting down any listeners.
    @Synchronized
    override fun close(): CompletableFuture<Unit> {
        closed = true
        val unbindFutures = activeListeners
            .map { (_, ch) -> ch }
            .map { it.close().toVoidCompletableFuture() }
        return CompletableFuture.allOf(*unbindFutures.toTypedArray())
            .thenCompose {
                synchronized(this@TcpTransport) {
                    val closeFutures = activeChannels.toMutableList()
                        .map { it.close().toVoidCompletableFuture() }
                    CompletableFuture.allOf(*closeFutures.toTypedArray())
                }
            }.thenApply { }
    }

    @Synchronized
    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler, streamHandler: StreamHandler): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")
        return server.clone()
            .childHandler(nettyInitializer { ch ->
                registerChannel(ch)
                val (channelHandler, connFuture) = createConnectionHandler(streamHandler, false)
                ch.pipeline().addLast(channelHandler)
                connFuture.thenAccept { connHandler.accept(it) }
            })
            .bind(fromMultiaddr(addr))
            .also { ch ->
                activeListeners += addr to ch.channel()
                ch.channel().closeFuture().addListener {
                    synchronized(this@TcpTransport) {
                        activeListeners -= addr
                    }
                }
            }
            .toVoidCompletableFuture()
    }

    @Synchronized
    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return activeListeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    }

    @Synchronized
    override fun dial(addr: Multiaddr, streamHandler: StreamHandler): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")
        val (channelHandler, connFuture) = createConnectionHandler(streamHandler, true)
        return client.clone()
            .handler(channelHandler)
            .connect(fromMultiaddr(addr))
            .also { registerChannel(it.channel()) }
            .toCompletableFuture().thenCompose { connFuture }
    }

    @Synchronized
    private fun registerChannel(ch: Channel): Channel {
        if (closed) {
            ch.close()
        } else {
            activeChannels += ch
            ch.closeFuture().addListener { activeChannels -= ch }
        }
        return ch
    }

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = addr.getStringComponents().find { p -> p.first in arrayOf(IP4, IP6, DNSADDR) }
            ?.second ?: throw Libp2pException("Missing IP4/IP6/DNSADDR in multiaddress $addr")
        val port = addr.getStringComponents().find { p -> p.first == TCP }
            ?.second ?: throw Libp2pException("Missing TCP in multiaddress $addr")
        return InetSocketAddress(host, port.toInt())
    }
}