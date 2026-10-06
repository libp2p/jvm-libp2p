package io.libp2p.transport.implementation

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Libp2pException
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrDns
import io.libp2p.core.multiformats.Protocol
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
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture

/**
 * A plain `NettyTransport` without embedded security and muxer
 */
abstract class PlainNettyTransport(
    private val upgrader: ConnectionUpgrader
) : NettyTransport { // class NettyTransportBase
    // `closed` is read in listen()/dial()/registerChannel() and written in close().
    // Made @Volatile to pair with the `synchronized(this@PlainNettyTransport)` blocks
    // that read it under-lock — see the comment on close() for why both are needed.
    @Volatile
    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)

    // `listeners` and `channels` are mutated by listen()/dial() under
    // `synchronized(this@PlainNettyTransport)`. close() must acquire the SAME
    // monitor before reading either map — see close() for the race that
    // motivated this. Holding the monitor while iterating also guarantees we
    // are reading a snapshot rather than a concurrently-mutated collection.
    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    private var workerGroup by lazyVar {
        MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    }
    private var bossGroup by lazyVar {
        MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    }

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
        // Take a consistent snapshot of the listener and child-channel collections
        // under the same monitor that listen() / dial() / registerChannel() use to
        // mutate them.
        //
        // Prior to this synchronization close() read `listeners` and `channels`
        // without holding the transport monitor. listen() writes to `listeners`
        // inside `synchronized(this@PlainNettyTransport)` AFTER calling
        // `listener.bind(addr)` — the bind task is already submitted to the boss
        // event loop at this point (so the port will be bound), but the map
        // update has not yet happened. A concurrent close() in that microsecond
        // window observed an empty map, scheduled no channel close, and proceeded
        // straight to shutdownGracefully. Netty's event loop still ran the
        // queued bind task before terminating (so the port DID get bound), but
        // the channel was never explicitly closed — leaving the OS file
        // descriptor open for the lifetime of the JVM.
        //
        // Setting `closed = true` inside the synchronized block also pairs with
        // the closed-check that listen() now performs INSIDE its own synchronized
        // block: any listen() that arrives after this close()'s sync acquire is
        // guaranteed to observe `closed = true` and reject the bind, so no new
        // listener can slip in during the shutdownGracefully phase below.
        val listenersToClose: List<Channel>
        val channelsToClose: List<Channel>
        synchronized(this@PlainNettyTransport) {
            closed = true
            listenersToClose = listeners.values.toList()
            channelsToClose = channels.toList()
        }

        val unbindsCompleted = listenersToClose
            .map { it.close().toVoidCompletableFuture() }

        val channelsClosed = channelsToClose
            .map { it.close().toVoidCompletableFuture() }

        val everythingThatNeedsToClose = unbindsCompleted.union(channelsClosed)
        val allClosed = CompletableFuture.allOf(*everythingThatNeedsToClose.toTypedArray())

        return allClosed.thenCompose {
            CompletableFuture.allOf(
                workerGroup.shutdownGracefully().toVoidCompletableFuture(),
                bossGroup.shutdownGracefully().toVoidCompletableFuture()
            ).thenApply { }
        }
    } // close

    override fun listen(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Unit> {
        // Hold the transport monitor for the entire `closed`-check ->
        // `listener.bind(addr)` -> `listeners += ...` sequence. Splitting these
        // across the lock boundary (as the prior shape did) opens a race window:
        // after bind() returns, the bind task is already on the boss event loop
        // queue (so the port WILL be bound when the event loop drains), but the
        // listener channel is not yet registered in `listeners`. A concurrent
        // close() that reads `listeners` in that window observes an empty map,
        // closes no listener channel, and proceeds straight to
        // shutdownGracefully — which still runs the queued bind task before the
        // event loop terminates. The result is a permanently-bound port with no
        // owning channel to close. See close() for the matching read-under-lock
        // and the regression test PlainNettyTransportConcurrentListenCloseTest
        // for the deterministic reproduction.
        val bindComplete = synchronized(this@PlainNettyTransport) {
            if (closed) throw Libp2pException("Transport is closed")

            val connectionBuilder = makeConnectionBuilder(connHandler, false, preHandler = preHandler)
            val channelHandler = serverTransportBuilder(connectionBuilder, addr) ?: connectionBuilder

            val listener = server.clone()
                .childHandler(
                    nettyInitializer { init ->
                        registerChannel(init.channel)
                        init.addLastLocal(channelHandler)
                    }
                )

            val cf = listener.bind(fromMultiaddr(addr))
            listeners += addr to cf.channel()
            cf.channel().closeFuture().addListener {
                synchronized(this@PlainNettyTransport) {
                    listeners -= addr
                }
            }
            cf
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

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        val remotePeerId = addr.getPeerId()
        val connectionBuilder = makeConnectionBuilder(connHandler, true, remotePeerId, preHandler)
        val channelHandler = clientTransportBuilder(connectionBuilder, addr) ?: connectionBuilder

        val chanFuture = client.clone()
            .handler(channelHandler)
            .connect(fromMultiaddr(addr))
            .also { registerChannel(it.channel()) }

        val connection = chanFuture.toCompletableFuture()
            .thenCompose { connectionBuilder.connectionEstablished }
        connection.whenComplete { _, _ ->
            if (connection.isCancelled) {
                chanFuture.channel().close()
            }
        }
        return connection
    } // dial

    protected abstract fun clientTransportBuilder(
        connectionBuilder: ConnectionBuilder,
        addr: Multiaddr
    ): ChannelHandler?

    private fun registerChannel(ch: Channel) {
        // Perform the closed-check and the map mutation atomically. If we
        // checked `closed` outside the monitor and then acquired it, a
        // concurrent close() could observe `closed = true` and snapshot the
        // `channels` collection in the window between the check and the add,
        // missing this channel entirely — same shape as the listen() race
        // documented on close(). Doing both under one acquire makes that
        // window unrepresentable.
        synchronized(this@PlainNettyTransport) {
            if (closed) {
                ch.close()
                return
            }

            channels += ch
            ch.closeFuture().addListener {
                synchronized(this@PlainNettyTransport) {
                    channels -= ch
                }
            }
        }
    } // registerChannel

    private fun makeConnectionBuilder(
        connHandler: ConnectionHandler,
        initiator: Boolean,
        remotePeerId: PeerId? = null,
        preHandler: ChannelVisitor<P2PChannel>?
    ) = ConnectionBuilder(
        this,
        upgrader,
        connHandler,
        initiator,
        remotePeerId,
        preHandler
    )

    protected fun handlesHost(addr: Multiaddr) =
        addr.hasAny(Protocol.IP4, Protocol.IP6, Protocol.DNS4, Protocol.DNS6, Protocol.DNSADDR)

    protected fun hostFromMultiaddr(addr: Multiaddr): String {
        val resolvedAddresses = MultiaddrDns.resolve(addr)
        if (resolvedAddresses.isEmpty()) {
            throw Libp2pException("Could not resolve $addr to an IP address")
        }

        return resolvedAddresses[0].components.find {
            it.protocol in arrayOf(Protocol.IP4, Protocol.IP6)
        }?.stringValue ?: throw Libp2pException("Missing IP4/IP6 in multiaddress $addr")
    }

    protected fun portFromMultiaddr(addr: Multiaddr) =
        addr.components.find { p -> p.protocol == Protocol.TCP }
            ?.stringValue?.toInt() ?: throw Libp2pException("Missing TCP in multiaddress $addr")

    private fun fromMultiaddr(addr: Multiaddr): InetSocketAddress {
        val host = hostFromMultiaddr(addr)
        val port = portFromMultiaddr(addr)
        return InetSocketAddress(host, port)
    } // fromMultiaddr

    override fun localAddress(nettyChannel: Channel): Multiaddr = toMultiaddr(nettyChannel.localAddress())
    override fun remoteAddress(nettyChannel: Channel): Multiaddr = toMultiaddr(nettyChannel.remoteAddress())

    abstract fun toMultiaddr(addr: SocketAddress): Multiaddr
}
