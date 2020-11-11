package io.libp2p.transport.implementation

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrDns
import io.libp2p.core.multiformats.Protocol
import io.libp2p.core.transport.Transport
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

abstract class NettyTransport(
    private val upgrader: ConnectionUpgrader
) : Transport {
    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)

    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    private var workerGroup by lazyVar { NioEventLoopGroup() }
    private var bossGroup by lazyVar { NioEventLoopGroup(1) }

    private var client by lazyVar {
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

    override val activeListeners: Int
        get() = listeners.size
    override val activeConnections: Int
        get() = channels.size

    override fun listenAddresses(): List<Multiaddr> {
        return listeners.values.map {
            toMultiaddr(it.localAddress() as InetSocketAddress)
        }
    }

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
        val allClosed = CompletableFuture.allOf(*everythingThatNeedsToClose.toTypedArray())

        return allClosed.thenApply {
            workerGroup.shutdownGracefully()
            bossGroup.shutdownGracefully()
            Unit
        }
    } // close

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        val connectionBuilder = makeConnectionBuilder(connHandler, false)
        val channelHandler = serverTransportBuilder(connectionBuilder, addr) ?: connectionBuilder

        val listener = server.clone()
            .childHandler(
                nettyInitializer { init ->
                    registerChannel(init.channel)
                    init.addLastLocal(channelHandler)
                }
            )

        val bindComplete = listener.bind(fromMultiaddr(addr))

        bindComplete.also {
            synchronized(this@NettyTransport) {
                listeners += addr to it.channel()
                it.channel().closeFuture().addListener {
                    synchronized(this@NettyTransport) {
                        listeners -= addr
                    }
                }
            }
        }

        return bindComplete.toVoidCompletableFuture()
    } // listener

    protected abstract fun serverTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler?

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return listeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    } // unlisten

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler):
        CompletableFuture<Connection> {
            if (closed) throw Libp2pException("Transport is closed")

            val remotePeerId = addr.getStringComponent(Protocol.P2P)?.let { PeerId.fromBase58(it) }
            val connectionBuilder = makeConnectionBuilder(connHandler, true, remotePeerId)
            val channelHandler = clientTransportBuilder(connectionBuilder, addr) ?: connectionBuilder

            val chanFuture = client.clone()
                .handler(channelHandler)
                .connect(fromMultiaddr(addr))
                .also { registerChannel(it.channel()) }

            return chanFuture.toCompletableFuture()
                .thenCompose { connectionBuilder.connectionEstablished }
        } // dial

    protected abstract fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler?

    private fun registerChannel(ch: Channel) {
        if (closed) {
            ch.close()
            return
        }

        synchronized(this@NettyTransport) {
            channels += ch
            ch.closeFuture().addListener {
                synchronized(this@NettyTransport) {
                    channels -= ch
                }
            }
        }
    } // registerChannel

    private fun makeConnectionBuilder(
        connHandler: ConnectionHandler,
        initiator: Boolean,
        remotePeerId: PeerId? = null
    ) = ConnectionBuilder(
        this,
        upgrader,
        connHandler,
        initiator,
        remotePeerId
    )

    protected fun handlesHost(addr: Multiaddr) =
        addr.hasAny(Protocol.IP4, Protocol.IP6, Protocol.DNS4, Protocol.DNS6, Protocol.DNSADDR)

    protected fun hostFromMultiaddr(addr: Multiaddr): String {
        val resolvedAddresses = MultiaddrDns.resolve(addr)
        if (resolvedAddresses.isEmpty())
            throw Libp2pException("Could not resolve $addr to an IP address")

        return resolvedAddresses[0].filterStringComponents().find {
            it.first in arrayOf(Protocol.IP4, Protocol.IP6)
        }?.second ?: throw Libp2pException("Missing IP4/IP6 in multiaddress $addr")
    }

    protected fun portFromMultiaddr(addr: Multiaddr) =
        addr.filterStringComponents().find { p -> p.first == Protocol.TCP }
            ?.second?.toInt() ?: throw Libp2pException("Missing TCP in multiaddress $addr")

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        return InetSocketAddress(host, port)
    } // fromMultiaddr

    abstract fun toMultiaddr(addr: InetSocketAddress): Multiaddr
} // class NettyTransportBase
