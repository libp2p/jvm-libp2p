package io.libp2p.transport.quic

import io.libp2p.core.*
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multiformats.MultiaddrDns
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
import io.libp2p.security.tls.verifyAndExtractIdentity
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.NettyTransport
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.AdaptiveByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.channel.*
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

enum class AddressFamily { IPV4, IPV6 }

/**
 * Whether an inbound hole-punched connection presenting libp2p identity [actual] is acceptable for
 * a hole punch that targeted [expected]. A null [expected] (dial multiaddr carried no peer id) means
 * any valid identity is accepted, matching the behaviour of a normal inbound server connection.
 */
internal fun holePunchIdentityMatches(expected: PeerId?, actual: PeerId): Boolean =
    expected == null || expected == actual

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

    /** Tracks active listener DatagramChannels by address family. */
    internal val listenerChannelsByFamily = ConcurrentHashMap<AddressFamily, Channel>()

    /**
     * A pending hole punch: the future completed when the inbound QUIC connection arrives, plus the
     * peer id we expect that connection to present (null if the dial multiaddr carried no peer id).
     */
    private data class PendingHolePunch(
        val expectedPeerId: PeerId?,
        val future: CompletableFuture<QuicChannel>
    )

    /** Pending hole punches keyed by the remote address we are trying to reach. */
    private val pendingHolePunches = ConcurrentHashMap<InetSocketAddress, PendingHolePunch>()

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
            .channel(NioDatagramChannel::class.java)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
    }

    private var server by lazyVar {
        Bootstrap().group(workerGroup)
            .channel(NioDatagramChannel::class.java)
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
            val family = if ((fromMultiaddr(addr) as InetSocketAddress).address is Inet6Address) AddressFamily.IPV6 else AddressFamily.IPV4
            synchronized(this@QuicTransport) {
                listeners += addr to it.channel()
                it.channel().closeFuture().addListener { _ ->
                    synchronized(this@QuicTransport) {
                        listeners -= addr
                    }
                    listenerChannelsByFamily.remove(family, it.channel())
                }
            }
            // Register after sync block to avoid holding lock during ConcurrentHashMap update
            listenerChannelsByFamily[family] = it.channel()
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

        // Always bind the dial socket to an ephemeral port. We deliberately do NOT try to reuse an
        // active listener's port: two UDP sockets cannot share a port without SO_REUSEPORT, and
        // even with it the kernel may deliver a dial's response packets to the listener socket
        // (which runs a QUIC *server* codec), silently breaking the handshake. NAT-consistent
        // dialing for hole punching is handled separately by dialAsListener, which reuses the
        // listener socket directly.
        val quicConnFuture: CompletableFuture<QuicChannel> = client.clone()
            .handler(requestsHandler)
            .bind(InetSocketAddress(0))
            .toCompletableFuture()
            .thenCompose { udpChannel ->
                QuicChannel.newBootstrap(udpChannel)
                    .streamOption(ChannelOption.ALLOCATOR, allocator)
                    .option(ChannelOption.AUTO_READ, true)
                    .option(ChannelOption.ALLOCATOR, allocator)
                    .remoteAddress(targetAddr)
                    .handler(
                        nettyInitializer {
                            val quicCh = it.channel as QuicChannel
                            val connection = ConnectionOverNetty(quicCh, this@QuicTransport, true)
                            // ConnectionOverNetty.init sets quicCh.attr(CONNECTION) = connection,
                            // so InboundStreamHandler can find it before thenApply runs.
                            connection.setMuxerSession(QuicMuxerSession(quicCh, connection))
                        }
                    )
                    .streamHandler(InboundStreamHandler(multistreamProtocol, protocols))
                    .connect()
                    .toCompletableFuture()
                    .whenComplete { quicCh, ex ->
                        // The QuicChannel rides on its own ephemeral datagram socket. Closing a
                        // QuicChannel does NOT close its parent datagram channel, so we must tie the
                        // two lifecycles together. Otherwise every dial leaks a NioDatagramChannel
                        // (plus its codec, native quiche config and queued direct buffers).
                        if (ex != null || quicCh == null) {
                            udpChannel.close()
                        } else {
                            // Register as soon as the connection is established — before the dependent
                            // thenApply runs — so that a dial cancelled mid-race (whose thenApply is
                            // skipped) still has its channel tracked, closeable on transport shutdown,
                            // and counted in activeConnections.
                            registerChannel(quicCh)
                            quicCh.closeFuture().addListener { udpChannel.close() }
                        }
                    }
            }

        val connectionFuture: CompletableFuture<Connection> = quicConnFuture
            .thenApply {
                try {
                    val connection = it.attr(CONNECTION).get() as ConnectionOverNetty

                    val peerCerts = it.sslEngine()?.session?.peerCertificates
                        ?: throw Libp2pException("No peer certificates available after QUIC handshake with $addr")

                    // remoteId and remotePubKey both come from the libp2p host key carried in
                    // the certificate extension (verifyAndExtractIdentity) — never the ephemeral
                    // cert subject key returned by getPublicKeyFromCert. This preserves the
                    // SecureChannel.Session invariant PeerId.fromPubKey(remotePubKey) == remoteId.
                    // peerCerts is read from this connection's own SSL session (above), not shared
                    // TrustManager state, avoiding a race between concurrent handshakes.
                    val remoteIdentity = verifyAndExtractIdentity(peerCerts)
                    val expectedPeerId = addr.getPeerId()
                    if (expectedPeerId != null && remoteIdentity.peerId != expectedPeerId) {
                        // Defence-in-depth: Libp2pTrustManager already enforces this during the
                        // handshake when a dial target is known, but assert at the session-
                        // construction site so a misconfigured trust manager cannot silently leak
                        // the wrong identity into the connection.
                        throw Libp2pException(
                            "Remote peer presented libp2p pubkey for ${remoteIdentity.peerId} but dial target was $expectedPeerId"
                        )
                    }
                    connection.setSecureSession(
                        SecureChannel.Session(
                            PeerId.fromPubKey(localKey.publicKey()),
                            remoteIdentity.peerId,
                            remoteIdentity.pubKey,
                            null
                        )
                    )

                    // Warm the address cache while the QuicChannel is still live; once closed it
                    // frees native state and remoteSocketAddress() returns null.
                    connection.cacheAddresses()

                    preHandler?.also { visitor -> visitor.visit(connection) }
                    connHandler.handleConnection(connection)

                    connection
                } catch (e: Throwable) {
                    // Post-handshake setup failed — close the established QuicChannel so neither it
                    // nor its underlying datagram channel is leaked.
                    it.close()
                    throw e
                }
            }

        // NetworkImpl.connect() cancels losing dial futures in the multi-address race. Cancelling
        // this dependent future does NOT propagate upstream to quicConnFuture, so if the handshake
        // later completes, the thenApply body above is skipped and the established QuicChannel is
        // never registered, handed to the caller, nor closed. Close it explicitly on cancellation
        // so neither it nor its underlying datagram channel is leaked (mirrors the TCP transport).
        connectionFuture.whenComplete { _, _ ->
            if (connectionFuture.isCancelled) {
                quicConnFuture.thenAccept { it.close() }
            }
        }
        return connectionFuture
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
                                // Read peer certificates from this connection's own SSL session
                                // rather than shared TrustManager state, avoiding a race between
                                // concurrent handshakes. Both remoteId and remotePubKey come from
                                // the libp2p host key in the cert extension (verifyAndExtractIdentity),
                                // never the ephemeral cert subject key — preserving the contract
                                // PeerId.fromPubKey(remotePubKey) == remoteId.
                                val peerCerts = (ctx.channel() as QuicChannel).sslEngine()
                                    ?.session?.peerCertificates
                                if (!peerCerts.isNullOrEmpty()) {
                                    val remoteIdentity = verifyAndExtractIdentity(peerCerts)

                                    logger.info("Handshake completed with remote peer id: {}", remoteIdentity.peerId)

                                    // Check if this connection is completing a pending hole punch.
                                    val remoteAddr = ctx.channel().remoteAddress() as? InetSocketAddress
                                    val pending = if (remoteAddr != null) {
                                        pendingHolePunches.remove(remoteAddr)
                                    } else {
                                        null
                                    }

                                    // For a hole punch, validate the inbound peer's identity against
                                    // the dial target BEFORE exposing the connection. The server
                                    // pipeline's InboundStreamHandler would otherwise dispatch streams
                                    // to protocol handlers during the window before the dialAsListener
                                    // check closes the channel, giving a wrong-identity peer that
                                    // reached the expected UDP tuple an unauthenticated-by-target
                                    // window into inbound protocols.
                                    if (pending != null &&
                                        !holePunchIdentityMatches(pending.expectedPeerId, remoteIdentity.peerId)
                                    ) {
                                        logger.warn(
                                            "Hole-punched peer presented libp2p id {} but dial target was {}; closing",
                                            remoteIdentity.peerId,
                                            pending.expectedPeerId
                                        )
                                        ctx.close()
                                        pending.future.completeExceptionally(
                                            Libp2pException(
                                                "Remote peer presented libp2p pubkey for ${remoteIdentity.peerId} " +
                                                    "but dial target was ${pending.expectedPeerId}"
                                            )
                                        )
                                        return
                                    }

                                    connection.setSecureSession(
                                        SecureChannel.Session(
                                            PeerId.fromPubKey(localKey.publicKey()),
                                            remoteIdentity.peerId,
                                            remoteIdentity.pubKey,
                                            null
                                        )
                                    )

                                    // Remove this handler as it's no longer needed
                                    ctx.pipeline().remove(this)

                                    // Warm the address cache while the QuicChannel is still live;
                                    // once closed it frees native state and remoteSocketAddress()
                                    // returns null.
                                    connection.cacheAddresses()

                                    if (pending != null) {
                                        // Route this validated inbound connection to the waiting
                                        // hole-punch caller.
                                        pending.future.complete(ctx.channel() as QuicChannel)
                                    } else {
                                        // Normal path: deliver to the connection handler
                                        preHandler?.also { visitor -> visitor.visit(connection) }
                                        connHandler.handleConnection(connection)
                                    }
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

    /**
     * Dial a remote peer using the hole-punching technique: send UDP probe packets from the
     * active listener socket to open NAT mappings, then wait for the remote peer to connect back
     * on the same port.
     *
     * Requires an active listener for the same address family as [addr].
     * The returned future completes when the inbound QUIC connection from the remote peer arrives,
     * or fails with a [java.util.concurrent.TimeoutException] after 5 seconds.
     */
    fun dialAsListener(
        addr: Multiaddr,
        connHandler: ConnectionHandler,
        preHandler: ChannelVisitor<P2PChannel>?
    ): CompletableFuture<Connection> {
        if (closed) throw Libp2pException("Transport is closed")
        val targetAddr = fromMultiaddr(addr) as InetSocketAddress
        val family = if (targetAddr.address is Inet6Address) AddressFamily.IPV6 else AddressFamily.IPV4
        val listenerChannel = listenerChannelsByFamily[family]
            ?: throw Libp2pException(
                "Hole punch requires an active listener for the same address family as $addr"
            )

        val inboundFuture = CompletableFuture<QuicChannel>()
        // Record the expected peer id so the inbound handshake can be validated against the dial
        // target before the connection is exposed (see channelActive in serverTransportBuilder).
        pendingHolePunches[targetAddr] = PendingHolePunch(addr.getPeerId(), inboundFuture)

        // Send UDP probe packets to open NAT mappings on both sides.
        // Probes are raw UDP datagrams — not QUIC frames.
        val probeBytes = ByteArray(64).also { java.util.concurrent.ThreadLocalRandom.current().nextBytes(it) }
        val probeBuf = io.netty.buffer.Unpooled.wrappedBuffer(probeBytes)
        val probeTask = workerGroup.scheduleAtFixedRate(
            {
                val packet = io.netty.channel.socket.DatagramPacket(probeBuf.retainedDuplicate(), targetAddr)
                listenerChannel.writeAndFlush(packet)
            },
            0L,
            50L,
            TimeUnit.MILLISECONDS
        )

        return inboundFuture
            .orTimeout(5, TimeUnit.SECONDS)
            .whenComplete { _, _ ->
                probeTask.cancel(false)
                probeBuf.release()
                pendingHolePunches.remove(targetAddr)
            }
            .thenApply { quicChannel ->
                registerChannel(quicChannel)
                try {
                    val connection = ConnectionOverNetty(quicChannel, this@QuicTransport, false)
                    connection.setMuxerSession(QuicMuxerSession(quicChannel, connection))

                    val peerCerts = quicChannel.sslEngine()?.session?.peerCertificates
                        ?: throw Libp2pException("No peer certificates in hole-punched connection from $addr")

                    // remoteId and remotePubKey must both come from the libp2p host key in the cert
                    // extension (verifyAndExtractIdentity), never the ephemeral cert subject key, so
                    // that the invariant PeerId.fromPubKey(remotePubKey) == remoteId holds on the
                    // hole-punch path too.
                    val remoteIdentity = verifyAndExtractIdentity(peerCerts)
                    val expectedPeerId = addr.getPeerId()
                    if (expectedPeerId != null && remoteIdentity.peerId != expectedPeerId) {
                        throw Libp2pException(
                            "Remote peer presented libp2p pubkey for ${remoteIdentity.peerId} but dial target was $expectedPeerId"
                        )
                    }

                    connection.setSecureSession(
                        SecureChannel.Session(
                            PeerId.fromPubKey(localKey.publicKey()),
                            remoteIdentity.peerId,
                            remoteIdentity.pubKey,
                            null
                        )
                    )

                    // Warm the address cache while the QuicChannel is still live; once closed it
                    // frees native state and remoteSocketAddress() returns null.
                    connection.cacheAddresses()

                    preHandler?.also { visitor -> visitor.visit(connection) }
                    connHandler.handleConnection(connection)
                    quicChannel.attr(CONNECTION).set(connection)
                    connection
                } catch (e: Throwable) {
                    // Post-handshake setup failed — close the registered QuicChannel so neither it
                    // nor its underlying datagram channel is leaked (mirrors the normal dial path).
                    quicChannel.close()
                    throw e
                }
            }
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
