package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
import io.libp2p.etc.REMOTE_PEER_ID
import io.libp2p.etc.types.forward
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.transport.ConnectionUpgrader
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

abstract class NettyTransportBase(
    private val upgrader: ConnectionUpgrader
) : Transport {
    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)

    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    private var workerGroup by lazyVar { NioEventLoopGroup() }
    private var bossGroup by lazyVar { workerGroup }

    private var nettyClient by lazyVar {
        Bootstrap().apply {
            group(workerGroup)
            channel(NioSocketChannel::class.java)
            option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
        }
    }

    private var nettyServer by lazyVar {
        ServerBootstrap().apply {
            group(bossGroup, workerGroup)
            channel(NioServerSocketChannel::class.java)
        }
    }

    override val activeListeners: Int
        get() = listeners.size
    override val activeConnections: Int
        get() = channels.size

    override fun initialize() {
    }

    override fun close(): CompletableFuture<Unit> {
        closed = true

        val unbindsCompleted = listeners
            .map { (_, ch) -> ch }
            .map { it.close().toVoidCompletableFuture() }

        val channelsClosed = channels
            .toMutableList() // need a copy to avoid potential co-modification problems
            .map { it.close().toVoidCompletableFuture() }

        val everythingThatNeedsToClose = unbindsCompleted.union(channelsClosed)

        return CompletableFuture
            .allOf(*everythingThatNeedsToClose.toTypedArray())
            .thenApply { }
    } // close

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        val server = nettyServer.clone()
        serverInitializer(addr)?.also { server.childHandler(it) }

        val listener = server.clone()
            .childHandler(
                nettyInitializer { ch ->
                    registerChannel(ch)
                    val (channelHandler, _) = createConnectionHandler(connHandler, false)
                    ch.pipeline().addLast(channelHandler)
                }
            )

        val bindComplete = listener.bind(fromMultiaddr(addr))

        bindComplete.also {
            synchronized(this@NettyTransportBase) {
                listeners += addr to it.channel()
                it.channel().closeFuture().addListener {
                    synchronized(this@NettyTransportBase) {
                        listeners -= addr
                    }
                }
            }
        }

        return bindComplete.toVoidCompletableFuture()
    } // listener

    protected abstract fun serverInitializer(addr: Multiaddr): ChannelHandler?

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return listeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    } // unlisten

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        val remotePeerId = addr.getStringComponent(Protocol.P2P)?.let { PeerId.fromBase58(it) }
        val (channelHandler, connFuture) = createConnectionHandler(connHandler, true, remotePeerId)

        val client = nettyClient.clone()
        clientInitializer(addr)?.also {
            client.handler(it)
        }

        val chanFuture = client
            .handler(channelHandler)
            .connect(fromMultiaddr(addr))
            .also { registerChannel(it.channel()) }

        return chanFuture.toCompletableFuture().thenCompose { connFuture }
    } // dial

    protected abstract fun clientInitializer(addr: Multiaddr): ChannelHandler?

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

    private fun registerChannel(ch: Channel) {
        if (closed) {
            ch.close()
            return
        }

        synchronized(this@NettyTransportBase) {
            channels += ch
            ch.closeFuture().addListener {
                synchronized(this@NettyTransportBase) {
                    channels -= ch
                }
            }
        }
    } // registerChannel

    protected fun hostFromMultiaddr(addr: Multiaddr) =
        addr.filterStringComponents().find { p -> p.first in arrayOf(
            Protocol.IP4,
            Protocol.IP6,
            Protocol.DNSADDR
        ) }
            ?.second ?: throw Libp2pException("Missing IP4/IP6/DNSADDR in multiaddress $addr")

    protected fun portFromMultiaddr(addr: Multiaddr) =
        addr.filterStringComponents().find { p -> p.first == Protocol.TCP }
            ?.second?.toInt() ?: throw Libp2pException("Missing TCP in multiaddress $addr")

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        return InetSocketAddress(host, port)
    } // fromMultiaddr
} // class NettyTransportBase