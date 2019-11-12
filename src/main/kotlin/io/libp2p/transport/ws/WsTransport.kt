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
import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.transport.ConnectionUpgrader
import io.libp2p.transport.tcp.ConnectionOverNetty
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture

class WsTransport(
    val upgrader: ConnectionUpgrader
) : Transport {
    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)
    val activeListeners = mutableMapOf<Multiaddr, Channel>()
    val activeChannels = mutableListOf<Channel>()

    private var workerGroup by lazyVar { NioEventLoopGroup() }
    private var bossGroup by lazyVar { workerGroup }

    var client by lazyVar {
        Bootstrap().apply {
            group(workerGroup)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
        }
    }

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
    } // handles

    override fun close(): CompletableFuture<Unit> {
        closed = true

        val unbindsCompleted = activeListeners
            .map { (_, ch) -> ch }
            .map { it.close().toVoidCompletableFuture() }

        val channelsClosed = activeChannels
            .toMutableList() // need a copy to avoid potential co-modification problems
            .map { it.close().toVoidCompletableFuture() }

        val everythingThatNeedsToClose = unbindsCompleted.union(channelsClosed)

        return CompletableFuture
            .allOf(*everythingThatNeedsToClose.toTypedArray())
            .thenApply { }
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

    @Synchronized
    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        val remotePeerId = addr.getStringComponent(Protocol.P2P)?.let { PeerId.fromBase58(it) }
        val (channelHandler, connFuture) = createConnectionHandler(connHandler, true, remotePeerId)

        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        val url = "ws://${host}:${port}/"

        val chanFuture = client.clone()
            .handler(WebSocketClientInitializer(url))
            .handler(channelHandler)
            .connect(fromMultiaddr(addr))
            .also { registerChannel(it.channel()) }

        return chanFuture.toCompletableFuture().thenCompose { connFuture }
    } // dial

    private fun createConnectionHandler(
        connHandler: ConnectionHandler,
        initiator: Boolean,
        remotePeerId: PeerId? = null
    ): Pair<ChannelHandler, CompletableFuture<Connection>> {
        val connFuture = CompletableFuture<Connection>()
        return nettyInitializer { ch ->
            val connection = ConnectionOverNetty(ch, this, initiator)
            remotePeerId?.also { ch.attr(REMOTE_PEER_ID).set(it) }
            upgrader.establishSecureChannel(connection)
                .thenCompose {
                    connection.setSecureSession(it)
                    upgrader.establishMuxer(connection)
                }.thenApply {
                    connection.setMuxerSession(it)
                    connHandler.handleConnection(connection)
                    connection
                }
                .forward(connFuture)
        } to connFuture
    } // createConnectionHandler

    @Synchronized
    private fun registerChannel(ch: Channel): Channel {
        if (closed) {
            ch.close()
        } else {
            activeChannels += ch
            ch.closeFuture().addListener {
                synchronized(this@WsTransport) {
                    activeChannels -= ch
                }
            }
        }
        return ch
    } // registerChannel

    private fun hostFromMultiaddr(addr: Multiaddr) =
        addr.filterStringComponents().find { p -> p.first in arrayOf(
            Protocol.IP4,
            Protocol.IP6,
            Protocol.DNSADDR
        ) }
            ?.second ?: throw Libp2pException("Missing IP4/IP6/DNSADDR in multiaddress $addr")

    private fun portFromMultiaddr(addr: Multiaddr) =
        addr.filterStringComponents().find { p -> p.first == Protocol.TCP }
            ?.second?.toInt() ?: throw Libp2pException("Missing TCP in multiaddress $addr")

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        return InetSocketAddress(host, port)
    } // fromMultiaddr
} // class WsTransport