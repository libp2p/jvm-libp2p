package io.libp2p.transport.quic

import io.libp2p.core.*
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.unmarshalPublicKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrDns
import io.libp2p.core.multiformats.Multihash
import io.libp2p.core.multiformats.Protocol.*
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.MultistreamProtocolV1
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.STREAM
import io.libp2p.etc.types.*
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.security.tls.*
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.*
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.ssl.ClientAuth
import io.netty.incubator.codec.quic.*
import java.net.*
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class QuicTransport(
    private val localKey: PrivKey,
    private val certAlgorithm: String,
    private val protocols: List<ProtocolBinding<*>>
) : Transport {

    private var closed = false
    var connectTimeout = Duration.ofSeconds(15)

    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    private var workerGroup by lazyVar { NioEventLoopGroup() }
    private var bossGroup by lazyVar { NioEventLoopGroup(1) }
    private var allocator by lazyVar { PooledByteBufAllocator(true) }
    private var multistreamProtocol: MultistreamProtocol = MultistreamProtocolV1
    private var incomingMultistreamProtocol: MultistreamProtocol by lazyVar { multistreamProtocol }

    private var client by lazyVar {
        Bootstrap().group(workerGroup)
            .channel(
                if (Epoll.isAvailable()) {
                    EpollDatagramChannel::class.java
                } else {
                    NioDatagramChannel::class.java
                }
            )
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
    }

    companion object {
        @JvmStatic
        fun Ed25519(k: PrivKey, p: List<ProtocolBinding<*>>): QuicTransport {
            return QuicTransport(k, "Ed25519", p)
        }

        @JvmStatic
        fun Ecdsa(k: PrivKey, p: List<ProtocolBinding<*>>): QuicTransport {
            return QuicTransport(k, "ECDSA", p)
        }
    }

    private var server by lazyVar {
        Bootstrap().group(workerGroup)
            .channel(
                if (Epoll.isAvailable()) {
                    EpollDatagramChannel::class.java
                } else {
                    NioDatagramChannel::class.java
                }
            )
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
    }

    override fun listen(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        val channelHandler = serverTransportBuilder(connHandler, preHandler)

        val bindComplete = server.clone()
            .handler(
                nettyInitializer { init ->
                    registerChannel(init.channel)
                    init.addLastLocal(channelHandler)
                }
            )
            .localAddress(fromMultiaddr(addr))
            .bind()
            .sync()

        val res = CompletableFuture<Unit>()
        bindComplete.also {
            synchronized(this@QuicTransport) {
                listeners += addr to it.channel()
                it.channel().closeFuture().addListener {
                    synchronized(this@QuicTransport) {
                        listeners -= addr
                    }
                }
                println("Quic server listening on " + addr)
                res.complete(null)
            }
        }

        return res
    }

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return listeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    }

    private fun createStream(channel: Channel, connection: Connection): Stream {
        val stream = StreamOverNetty(channel, connection, true)
        channel.attr(STREAM).set(stream)
        return stream
    }

    override fun dial(addr: Multiaddr, connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?):
        CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        val trust = Libp2pTrustManager(Optional.ofNullable(addr.getPeerId()))
        val sslContext = quicSslContext(addr.getPeerId(), trust)
        val handler = QuicClientCodecBuilder()
            .sslEngineProvider({ q -> sslContext.newEngine(q.alloc()) })
            .maxIdleTimeout(15000, TimeUnit.MILLISECONDS)
            .sslTaskExecutor(workerGroup)
            .build()

        val chanFuture = QuicChannel.newBootstrap(
            client.clone()
                .handler(handler)
                .localAddress(0)
                .bind()
                .sync()
                .channel()
        )
            .streamOption(ChannelOption.ALLOCATOR, allocator)
            .option(ChannelOption.AUTO_READ, true)
            .option(ChannelOption.ALLOCATOR, allocator)
            .remoteAddress(fromMultiaddr(addr))
//            .handler(connHandler)
            .streamHandler(object : ChannelHandler {
                override fun handlerAdded(ctx: ChannelHandlerContext?) {
                    TODO("Not yet implemented")
                }

                override fun handlerRemoved(ctx: ChannelHandlerContext?) {
                    TODO("Not yet implemented")
                }

                @Deprecated("Deprecated in Java")
                override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
                    TODO("Not yet implemented")
                }
            })
            .connect()

        val res = CompletableFuture<Connection>()
        chanFuture.also { registerChannel(it.get()) }
        chanFuture.also {
            val connection = ConnectionOverNetty(it.get(), this, true)
            connection.setMuxerSession(object : StreamMuxer.Session {
                override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
                    var multistreamProtocol: MultistreamProtocol = MultistreamProtocolV1
                    var streamMultistreamProtocol: MultistreamProtocol by lazyVar { multistreamProtocol }
                    val multi = streamMultistreamProtocol.createMultistream(protocols)

                    val controller = CompletableFuture<T>()
                    val streamFut = CompletableFuture<Stream>()
                    val chanFut = it.get().createStream(
                        QuicStreamType.BIDIRECTIONAL,
                        object : ChannelHandler {
                            override fun handlerAdded(ctx: ChannelHandlerContext?) {
                                val stream = createStream(ctx!!.channel(), connection)
                                multi.toStreamHandler().handleStream(stream).forward(controller).apply { streamFut.complete(stream) }
                                ctx.pipeline().addLast()
                            }

                            override fun handlerRemoved(ctx: ChannelHandlerContext?) {
                                TODO("Not yet implemented handler removal")
                            }

                            @Deprecated("Deprecated in Java")
                            override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
                                TODO("Not yet implemented exception caught")
                            }
                        }
                    )
                    chanFut.apply {
                        val stream = createStream(chanFut.get(), connection)
//                        multi.initChannel(stream).forward(controller).apply { streamFut.complete(stream) }
                        multi.toStreamHandler().handleStream(stream).forward(controller).apply { streamFut.complete(stream) }
                    }
                    return StreamPromise(streamFut, controller)
                }
            })
            val pubHash = Multihash.of(addr.getPeerId()!!.bytes.toByteBuf())
            val remotePubKey = if (pubHash.desc.digest == Multihash.Digest.Identity)
                unmarshalPublicKey(pubHash.bytes.toByteArray())
            else
                getPublicKeyFromCert(arrayOf(trust.remoteCert!!))
            connection.setSecureSession(
                SecureChannel.Session(
                    PeerId.fromPubKey(localKey.publicKey()),
                    addr.getPeerId()!!,
                    remotePubKey,
                    null
                )
            )
            preHandler?.also { it.visit(connection) }
            connHandler.handleConnection(connection)
            res.complete(connection)
        }
        return res
    }

    private fun registerChannel(ch: Channel) {
        if (closed) {
            ch.close()
            return
        }

        synchronized(this@QuicTransport) {
            channels += ch
            ch.closeFuture().addListener {
                synchronized(this@QuicTransport) {
                    channels -= ch
                }
            }
        }
    }

    protected fun handlesHost(addr: Multiaddr) =
        addr.hasAny(IP4, IP6, DNS4, DNS6, DNSADDR)

    protected fun hostFromMultiaddr(addr: Multiaddr): String {
        val resolvedAddresses = MultiaddrDns.resolve(addr)
        if (resolvedAddresses.isEmpty())
            throw Libp2pException("Could not resolve $addr to an IP address")

        return resolvedAddresses[0].components.find {
            it.protocol in arrayOf(IP4, IP6)
        }?.stringValue ?: throw Libp2pException("Missing IP4/IP6 in multiaddress $addr")
    }
    override fun handles(addr: Multiaddr) =
        handlesHost(addr) &&
            addr.has(UDP) &&
            addr.has(QUIC) &&
            !addr.has(WS)

    fun quicSslContext(expectedRemotePeerId: PeerId?, trustManager: Libp2pTrustManager): QuicSslContext {
        val connectionKeys = if (certAlgorithm.equals("ECDSA")) generateEcdsaKeyPair() else generateEd25519KeyPair()
        val javaPrivateKey = getJavaKey(connectionKeys.first)
        val isClient = expectedRemotePeerId != null
        val cert = buildCert(localKey, connectionKeys.first)
        println("Building " + certAlgorithm + " keys and cert for peerid " + PeerId.fromPubKey(localKey.publicKey()))
        return (
            if (isClient)
                QuicSslContextBuilder.forClient().keyManager(javaPrivateKey, null, cert)
            else
                QuicSslContextBuilder.forServer(javaPrivateKey, null, cert).clientAuth(ClientAuth.REQUIRE)
            )
            .trustManager(trustManager)
            .applicationProtocols("libp2p")
            .build()
    }

    fun serverTransportBuilder(connHandler: ConnectionHandler, preHandler: ChannelVisitor<P2PChannel>?): ChannelHandler {
        val sslContext = quicSslContext(null, Libp2pTrustManager(Optional.empty()))
        return QuicServerCodecBuilder()
            .sslEngineProvider({ q -> sslContext.newEngine(q.alloc()) })
            .initialMaxStreamsBidirectional(10)
            .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
            .sslTaskExecutor(workerGroup)
            .tokenHandler(NoTokenHandler())
            .handler(object : ChannelInboundHandlerAdapter() {
                override fun channelRegistered(ctx: ChannelHandlerContext?) {
                    super.channelRegistered(ctx)
                    println("chan registered")
                    if (ctx != null) {
                        val connection = ConnectionOverNetty(ctx.channel(), this@QuicTransport, false)
//                        preHandler?.also { it.visit(connection) }
//                        connHandler.handleConnection(connection)
//                        incomingMultistreamProtocol.createMultistream(protocols).initChannel(connection)

//                        val connection = ctx.channel().attr(CONNECTION).get()
                        val stream = StreamOverNetty(ctx.channel(), connection, false)
                        ctx.channel().attr(STREAM).set(stream)
                        preHandler?.also { it.visit(connection) }
                        connHandler.handleConnection(connection)
                        val handler = incomingMultistreamProtocol.createMultistream(protocols).toStreamHandler()
                        handler.handleStream(stream)
                    }
                }

                override fun channelActive(ctx: ChannelHandlerContext) {
                    super.channelActive(ctx)
                    println("chan active")
                }

                override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                    super.channelRead(ctx, msg)
                    println("chan read " + msg)
                }
            })
            .streamHandler(object : ChannelHandler {
                override fun handlerAdded(ctx: ChannelHandlerContext?) {
                    println("Quic server side stream handler added")
                    val connection = ctx!!.channel().attr(CONNECTION).get()
                    connHandler.handleConnection(connection)
                }

                override fun handlerRemoved(ctx: ChannelHandlerContext?) {
                    TODO("Not yet implemented handler removal")
                }

                @Deprecated("Deprecated in Java")
                override fun exceptionCaught(ctx: ChannelHandlerContext?, cause: Throwable?) {
                    TODO("Not yet implemented exception caught")
                }
            })
            .build()
    }

    class NoTokenHandler() : QuicTokenHandler {
        override fun writeToken(out: ByteBuf?, dcid: ByteBuf?, address: InetSocketAddress?): Boolean {
            return false
        }

        override fun validateToken(token: ByteBuf?, address: InetSocketAddress?): Int {
            return -1
        }

        override fun maxTokenLength(): Int {
            return 0
        }
    }

    fun udpPortFromMultiaddr(addr: Multiaddr) =
        addr.components.find { p -> p.protocol == UDP }
            ?.stringValue?.toInt() ?: throw Libp2pException("Missing UDP in multiaddress $addr")

    fun fromMultiaddr(addr: Multiaddr): SocketAddress {
        val host = hostFromMultiaddr(addr)
        val port = udpPortFromMultiaddr(addr)
        return InetSocketAddress(host, port)
    }

    fun toMultiaddr(addr: InetSocketAddress): Multiaddr {
        val proto = when (addr.address) {
            is Inet4Address -> IP4
            is Inet6Address -> IP6
            else -> throw InternalErrorException("Unknown address type $addr")
        }
        return Multiaddr.empty()
            .withComponent(proto, addr.address.hostAddress)
            .withComponent(UDP, addr.port.toString())
            .withComponent(QUIC)
    }
}
