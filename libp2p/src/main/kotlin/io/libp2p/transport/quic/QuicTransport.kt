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
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.CONNECTION
import io.libp2p.etc.STREAM
import io.libp2p.etc.types.*
import io.libp2p.etc.util.MultiaddrUtils
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.security.tls.Libp2pTrustManager
import io.libp2p.security.tls.buildCert
import io.libp2p.security.tls.getJavaKey
import io.libp2p.security.tls.getPublicKeyFromCert
import io.libp2p.security.tls.verifyAndExtractPeerId
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.NettyTransport
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.AdaptiveByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.quic.*
import io.netty.handler.ssl.ClientAuth
import org.slf4j.LoggerFactory
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * QUIC transport for libp2p using QUIC v1 (RFC 9000) with TLS 1.3.
 *
 * Security and multiplexing are native to QUIC — no separate Noise/TLS negotiation
 * or Yamux/Mplex negotiation is performed. Only `/quic-v1` multiaddrs are supported.
 *
 * **Private networks (PSK) are not supported.** QUIC's mandatory TLS 1.3 cannot be
 * replaced with a pre-shared key scheme. Do not configure a PSK alongside this transport.
 */
class QuicTransport(
    private val localKey: PrivKey,
    private val certAlgorithm: String,
    private val protocols: List<ProtocolBinding<*>>,
    private val config: QuicConfig = QuicConfig()
) : NettyTransport {

    private val logger = LoggerFactory.getLogger(QuicTransport::class.java)

    private var closed = false

    private val connectTimeout get() = config.connectTimeout

    private val listeners = mutableMapOf<Multiaddr, Channel>()
    private val channels = mutableListOf<Channel>()

    /** Tracks active listener DatagramChannels by address family (true = IPv6, false = IPv4). */
    internal val listenerChannelsByFamily = ConcurrentHashMap<Boolean, Channel>()

    private var workerGroup by lazyVar {
        MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    }
    private var allocator by lazyVar { AdaptiveByteBufAllocator(true) }
    private var multistreamProtocol: MultistreamProtocol = MultistreamProtocolV1
    private var incomingMultistreamProtocol: MultistreamProtocol by lazyVar { multistreamProtocol }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun Ed25519(k: PrivKey, p: List<ProtocolBinding<*>>, config: QuicConfig = QuicConfig()): QuicTransport {
            return QuicTransport(k, "Ed25519", p, config)
        }

        @JvmStatic
        @JvmOverloads
        fun ECDSA(k: PrivKey, p: List<ProtocolBinding<*>>, config: QuicConfig = QuicConfig()): QuicTransport {
            return QuicTransport(k, "ECDSA", p, config)
        }

        private fun createStream(channel: QuicStreamChannel, connection: Connection, initiator: Boolean): Stream {
            val stream = QuicStream(channel, connection, initiator)
            channel.attr(STREAM).set(stream)
            return stream
        }
    }

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

        return allClosed.thenCompose {
            workerGroup.shutdownGracefully().toVoidCompletableFuture()
        }
    }

    override fun listen(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Unit> {
        if (closed) throw Libp2pException("Transport is closed")

        val channelHandler = serverTransportBuilder(connHandler, preHandler)

        val listener = server.clone()
            .handler(
                nettyInitializer {
                    registerChannel(it.channel)
                    it.addLastLocal(channelHandler)
                }
            )

        val bindComplete = listener.bind(fromMultiaddr(addr))

        bindComplete.also {
            val isIPv6 = (fromMultiaddr(addr) as InetSocketAddress).address is Inet6Address
            synchronized(this@QuicTransport) {
                listeners += addr to it.channel()
                it.channel().closeFuture().addListener { _ ->
                    synchronized(this@QuicTransport) {
                        listeners -= addr
                    }
                    listenerChannelsByFamily.remove(isIPv6, it.channel())
                }
            }
            // Register after sync block to avoid holding lock during ConcurrentHashMap update
            listenerChannelsByFamily[isIPv6] = it.channel()
        }

        return bindComplete.toVoidCompletableFuture().thenApply {
            logger.info("Quic server listening on {}", addr)
        }
    }

    override fun unlisten(addr: Multiaddr): CompletableFuture<Unit> {
        return listeners[addr]?.close()?.toVoidCompletableFuture()
            ?: throw Libp2pException("No listeners on address $addr")
    }

    override fun dial(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")

        val trustManager = Libp2pTrustManager(Optional.ofNullable(addr.getPeerId()))
        val sslContext = quicSslContext(true, trustManager)
        val requestsHandler = QuicClientCodecBuilder()
            .sslEngineProvider { q -> sslContext.newEngine(q.alloc()) }
            .sslTaskExecutor(workerGroup)
            .maxIdleTimeout(config.idleTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .initialMaxData(config.maxConnectionData)
            .initialMaxStreamsBidirectional(config.maxStreamsBidirectional)
            .initialMaxStreamDataBidirectionalRemote(config.maxStreamDataRemote)
            .initialMaxStreamDataBidirectionalLocal(config.maxStreamDataLocal)
            .initialMaxStreamsUnidirectional(0) // libp2p QUIC uses bidirectional streams only
            .activeMigration(false) // disable until address-change handling is implemented
            // Note: spin bit is not configurable in Netty's QUIC codec; quiche disables it by default
            .statelessResetToken(deriveStatelessResetToken())
            .build()

        val targetAddr = fromMultiaddr(addr) as InetSocketAddress
        val isIPv6 = targetAddr.address is Inet6Address
        val existingListenerChannel = listenerChannelsByFamily[isIPv6]

        // Attempt to bind the client socket to the same port as an active listener for
        // consistent NAT mappings. Falls back to an ephemeral port if binding fails
        // (SO_REUSEADDR alone may be insufficient for UDP port sharing on some platforms;
        // TODO: add SO_REUSEPORT support for Linux/Epoll).
        val bindAddr: InetSocketAddress = if (existingListenerChannel != null) {
            val listenerPort = (existingListenerChannel.localAddress() as? InetSocketAddress)?.port ?: 0
            val wildcard = if (isIPv6) "::" else "0.0.0.0"
            InetSocketAddress(wildcard, listenerPort)
        } else {
            InetSocketAddress(0) // ephemeral port
        }

        fun connectViaBoundChannel(boundAddr: InetSocketAddress): CompletableFuture<QuicChannel> {
            return client.clone()
                .handler(requestsHandler)
                .option(ChannelOption.SO_REUSEADDR, true)
                .bind(boundAddr)
                .toCompletableFuture()
                .thenCompose { udpChannel ->
                    QuicChannel.newBootstrap(udpChannel)
                        .streamOption(ChannelOption.ALLOCATOR, allocator)
                        .option(ChannelOption.AUTO_READ, true)
                        .option(ChannelOption.ALLOCATOR, allocator)
                        .remoteAddress(targetAddr)
                        .streamHandler(InboundStreamHandler(multistreamProtocol, protocols))
                        .connect()
                        .toCompletableFuture()
                }
        }

        val quicConnFuture: CompletableFuture<QuicChannel> = if (existingListenerChannel != null) {
            connectViaBoundChannel(bindAddr).exceptionallyCompose { ex ->
                // Port reuse failed (SO_REUSEADDR insufficient on this platform); fall back to ephemeral port.
                logger.debug("Could not reuse listener port {} for dial ({}), using ephemeral port", bindAddr, ex.message)
                connectViaBoundChannel(InetSocketAddress(0))
            }
        } else {
            connectViaBoundChannel(InetSocketAddress(0))
        }

        return quicConnFuture
            .thenApply {
                registerChannel(it)
                val connection = ConnectionOverNetty(it, this@QuicTransport, true)
                connection.setMuxerSession(QuicMuxerSession(it, connection))

                val peerCerts = it.sslEngine()?.session?.peerCertificates
                    ?: throw Libp2pException("No peer certificates available after QUIC handshake with $addr")

                val expectedPeerId = addr.getPeerId()
                val remotePeerId: PeerId
                val remotePubKey: io.libp2p.core.crypto.PubKey

                if (expectedPeerId != null) {
                    // PeerId was pre-validated by trustManager during TLS handshake.
                    // For inline-key peerIds (identity multihash), extract pubkey from the multihash.
                    val pubHash = Multihash.of(expectedPeerId.bytes.toByteBuf())
                    remotePubKey = if (pubHash.desc.digest == Multihash.Digest.Identity) {
                        unmarshalPublicKey(pubHash.bytes.toByteArray())
                    } else {
                        getPublicKeyFromCert(peerCerts)
                    }
                    remotePeerId = expectedPeerId
                } else {
                    // No PeerId known upfront — extract from TLS certificate post-handshake.
                    remotePubKey = getPublicKeyFromCert(peerCerts)
                    remotePeerId = verifyAndExtractPeerId(peerCerts)
                }

                connection.setSecureSession(
                    SecureChannel.Session(
                        PeerId.fromPubKey(localKey.publicKey()),
                        remotePeerId,
                        remotePubKey,
                        null
                    )
                )

                preHandler?.also { visitor -> visitor.visit(connection) }
                connHandler.handleConnection(connection)

                it.attr(CONNECTION).set(connection)

                connection
            }
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

    private fun handlesHost(addr: Multiaddr) =
        addr.hasAny(IP4, IP6, DNS4, DNS6, DNSADDR)

    private fun hostFromMultiaddr(addr: Multiaddr): String {
        val resolvedAddresses = MultiaddrDns.resolve(addr)
        if (resolvedAddresses.isEmpty()) {
            throw Libp2pException("Could not resolve $addr to an IP address")
        }

        return resolvedAddresses[0].components.find {
            it.protocol in arrayOf(IP4, IP6)
        }?.stringValue ?: throw Libp2pException("Missing IP4/IP6 in multiaddress $addr")
    }

    override fun handles(addr: Multiaddr) =
        handlesHost(addr) &&
            addr.has(UDP) &&
            addr.has(QUICV1) &&
            !addr.has(WS)

    private fun deriveStatelessResetToken(): ByteArray {
        // Derive a deterministic 16-byte token from the local identity key.
        // Using SHA-256 of (key_bytes + magic), truncated to 16 bytes.
        val keyBytes = localKey.raw()
        val magic = "libp2p-quic-stateless-reset".toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(keyBytes)
        digest.update(magic)
        return digest.digest().copyOf(16)
    }

    fun quicSslContext(isClient: Boolean, trustManager: Libp2pTrustManager): QuicSslContext {
        val connectionKeys = if (certAlgorithm == "ECDSA") generateEcdsaKeyPair() else generateEd25519KeyPair()
        val javaPrivateKey = getJavaKey(connectionKeys.first)
        val cert = buildCert(localKey, connectionKeys.first)
        logger.trace("Building {} keys and cert for peer id {}", certAlgorithm, PeerId.fromPubKey(localKey.publicKey()))
        return (
            if (isClient) {
                QuicSslContextBuilder.forClient().keyManager(javaPrivateKey, null, cert)
            } else {
                QuicSslContextBuilder.forServer(javaPrivateKey, null, cert).clientAuth(ClientAuth.REQUIRE)
            }
            )
            .trustManager(trustManager)
            .applicationProtocols("libp2p")
            .build()
    }

    fun serverTransportBuilder(
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): ChannelHandler {
        val trustManager = Libp2pTrustManager(Optional.empty())
        val sslContext = quicSslContext(false, trustManager)
        return QuicServerCodecBuilder()
            .sslEngineProvider { q -> sslContext.newEngine(q.alloc()) }
            .sslTaskExecutor(workerGroup)
            .tokenHandler(NoTokenHandler())
            .handler(
                nettyInitializer {
                    val connection = ConnectionOverNetty(it.channel, this@QuicTransport, false)

                    connection.setMuxerSession(QuicMuxerSession(it.channel as QuicChannel, connection))
                    it.channel.attr(CONNECTION).set(connection)

                    // Add a handler to wait for channel activation (handshake completion)
                    it.channel.pipeline().addFirst(
                        "quic-handshake-waiter",
                        object : ChannelInboundHandlerAdapter() {
                            override fun channelActive(ctx: ChannelHandlerContext) {
                                // Extract peer certificates from this connection's SSL session,
                                // avoiding the race condition of reading shared TrustManager state.
                                val peerCerts = (ctx.channel() as QuicChannel).sslEngine()
                                    ?.session?.peerCertificates
                                if (!peerCerts.isNullOrEmpty()) {
                                    val remotePeerId = verifyAndExtractPeerId(peerCerts)
                                    val remotePublicKey = getPublicKeyFromCert(peerCerts)

                                    logger.info("Handshake completed with remote peer id: {}", remotePeerId)

                                    connection.setSecureSession(
                                        SecureChannel.Session(
                                            PeerId.fromPubKey(localKey.publicKey()),
                                            remotePeerId,
                                            remotePublicKey,
                                            null
                                        )
                                    )

                                    // Remove this handler as it's no longer needed
                                    ctx.pipeline().remove(this)

                                    // Now it's safe to call the connection handler
                                    preHandler?.also { visitor -> visitor.visit(connection) }
                                    connHandler.handleConnection(connection)
                                } else {
                                    // This should not happen if channelActive is called after handshake
                                    ctx.close()
                                    throw IllegalStateException("Remote certificate still not available after handshake")
                                }

                                super.channelActive(ctx)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                                logger.error("An error during handshake", cause)
                                ctx.close()
                            }
                        }
                    )
                }
            )
            .maxIdleTimeout(config.idleTimeout.toMillis(), TimeUnit.MILLISECONDS)
            .initialMaxData(config.maxConnectionData)
            .initialMaxStreamsBidirectional(config.maxStreamsBidirectional)
            .initialMaxStreamDataBidirectionalRemote(config.maxStreamDataRemote)
            .initialMaxStreamDataBidirectionalLocal(config.maxStreamDataLocal)
            .initialMaxStreamsUnidirectional(0) // libp2p QUIC uses bidirectional streams only
            .activeMigration(false) // disable until address-change handling is implemented
            // Note: spin bit is not configurable in Netty's QUIC codec; quiche disables it by default
            .statelessResetToken(deriveStatelessResetToken())
            .streamHandler(InboundStreamHandler(incomingMultistreamProtocol, protocols))
            .build()
    }

    class QuicMuxerSession(
        val ch: QuicChannel,
        val connection: ConnectionOverNetty
    ) : StreamMuxer.Session {

        override fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T> {
            val multistreamProtocol: MultistreamProtocol = MultistreamProtocolV1
            val streamMultistreamProtocol: MultistreamProtocol by lazyVar { multistreamProtocol }
            val multi = streamMultistreamProtocol.createMultistream(protocols)

            val controller = CompletableFuture<T>()

            val stream = ch.createStream(
                QuicStreamType.BIDIRECTIONAL,
                nettyInitializer {
                    val stream = createStream(it.channel as QuicStreamChannel, connection, true)
                    val streamHandler = multi.toStreamHandler()
                    streamHandler.handleStream(stream).forward(controller)
                }
            ).toCompletableFuture()
                .thenApply {
                    it.attr(STREAM).get()
                }
                .forwardException(controller)

            return StreamPromise(stream, controller)
        }
    }

    class InboundStreamHandler(
        val handler: MultistreamProtocol,
        val protocols: List<ProtocolBinding<*>>
    ) : ChannelInitializer<QuicStreamChannel>() {
        override fun initChannel(ch: QuicStreamChannel) {
            val connection = ch.parent().attr(CONNECTION).get()
            val stream = createStream(ch, connection, false)
            val streamHandler = handler.createMultistream(protocols).toStreamHandler()
            streamHandler.handleStream(stream)
        }
    }

    class NoTokenHandler : QuicTokenHandler {
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

    override fun localAddress(nettyChannel: Channel): Multiaddr =
        toMultiaddr((nettyChannel as QuicChannel).localSocketAddress()!!)

    override fun remoteAddress(nettyChannel: Channel): Multiaddr =
        toMultiaddr((nettyChannel as QuicChannel).remoteSocketAddress()!!)

    fun toMultiaddr(addr: SocketAddress): Multiaddr =
        MultiaddrUtils.inetSocketAddressToUdpMultiaddr(addr as InetSocketAddress)
            .withComponent(QUICV1)
}
