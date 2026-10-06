package io.libp2p.transport.quic

import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.security.tls.Libp2pTrustManager
import io.libp2p.security.tls.buildCert
import io.libp2p.security.tls.getJavaKey
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.handler.codec.quic.*
import io.netty.handler.ssl.ClientAuth
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.ImmediateEventExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Regression test for https://github.com/libp2p/jvm-libp2p/issues/532.
 *
 * Old quiche builds bundled in Netty seeded the receive flow-control window with
 * `min(initial_max_stream_data, 32 KiB)` instead of the advertised limit, so once a stream had
 * consumed its initial 10 MiB credit the receiver only extended it by 32 KiB at a time. The
 * receiver's first `MAX_STREAM_DATA` update must therefore re-advertise a full window, which is
 * visible in the quiche qlog as `maximum >= consumed + initial_max_stream_data`.
 */
class QuicFlowControlWindowTest {

    private val streamLimit = QuicConfig().maxStreamDataLocal
    private val connectionLimit = QuicConfig().maxConnectionData
    private val payloadSize = streamLimit + 2L * 1024 * 1024

    @Test
    fun `receiver re-advertises a full stream window after the initial credit is consumed`(@TempDir qlogDir: File) {
        val group = MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory())
        try {
            val received = AtomicLong()
            val allReceived = CountDownLatch(1)
            val serverCodec = QuicServerCodecBuilder()
                .sslEngineProvider { q -> sslContext(false).newEngine(q.alloc()) }
                .sslTaskExecutor(ImmediateEventExecutor.INSTANCE)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .initialMaxData(connectionLimit)
                .initialMaxStreamsBidirectional(256)
                .initialMaxStreamDataBidirectionalRemote(streamLimit)
                .initialMaxStreamDataBidirectionalLocal(streamLimit)
                .maxIdleTimeout(30, TimeUnit.SECONDS)
                .option(QuicChannelOption.QLOG, QLogConfiguration(qlogDir.absolutePath, "server", ""))
                .handler(ChannelInboundHandlerAdapter())
                .streamHandler(object : ChannelInitializer<QuicStreamChannel>() {
                    override fun initChannel(ch: QuicStreamChannel) {
                        ch.pipeline().addLast(object : ChannelInboundHandlerAdapter() {
                            override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                                if (received.addAndGet((msg as ByteBuf).readableBytes().toLong()) >= payloadSize) {
                                    allReceived.countDown()
                                }
                                ReferenceCountUtil.release(msg)
                            }
                        })
                    }
                })
                .build()
            val server = Bootstrap().group(group).channel(NioDatagramChannel::class.java)
                .handler(serverCodec)
                .bind(InetSocketAddress("127.0.0.1", 0)).sync().channel()

            val clientCodec = QuicClientCodecBuilder()
                .sslEngineProvider { q -> sslContext(true).newEngine(q.alloc()) }
                .sslTaskExecutor(ImmediateEventExecutor.INSTANCE)
                .initialMaxData(connectionLimit)
                .initialMaxStreamsBidirectional(256)
                .initialMaxStreamDataBidirectionalRemote(streamLimit)
                .initialMaxStreamDataBidirectionalLocal(streamLimit)
                .maxIdleTimeout(30, TimeUnit.SECONDS)
                .build()
            val clientUdp = Bootstrap().group(group).channel(NioDatagramChannel::class.java)
                .handler(clientCodec)
                .bind(InetSocketAddress(0)).sync().channel()
            val quicChannel = QuicChannel.newBootstrap(clientUdp)
                .handler(ChannelInboundHandlerAdapter())
                .streamHandler(ChannelInboundHandlerAdapter())
                .remoteAddress(server.localAddress())
                .connect().get(10, TimeUnit.SECONDS)
            val stream = quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, ChannelInboundHandlerAdapter())
                .get(10, TimeUnit.SECONDS)

            val chunk = ByteArray(64 * 1024)
            var sent = 0L
            while (sent < payloadSize) {
                stream.write(Unpooled.wrappedBuffer(chunk))
                sent += chunk.size
            }
            stream.flush()
            assertThat(allReceived.await(60, TimeUnit.SECONDS)).describedAs("payload received").isTrue()

            // Closing frees the quiche connections, which flushes the qlog.
            quicChannel.close().sync()
            clientUdp.close().sync()
            server.close().sync()

            val qlog = qlogDir.listFiles()!!.single().readText()
            val streamUpdates = Regex(""""frame_type":"max_stream_data","stream_id":(\d+),"maximum":(\d+)""")
                .findAll(qlog)
                .filter { it.groupValues[1] == stream.streamId().toString() }
                .map { it.groupValues[2].toLong() }
                .toList()
            assertThat(streamUpdates).describedAs("MAX_STREAM_DATA frames sent by receiver").isNotEmpty()
            // With a full window the update is sent once half the credit is consumed and re-advertises
            // consumed + streamLimit, i.e. at least 1.5x the initial limit. The collapsed window only
            // adds 32 KiB on top of the initial limit.
            assertThat(streamUpdates.first())
                .describedAs("first MAX_STREAM_DATA maximum (initial limit %d)", streamLimit)
                .isGreaterThanOrEqualTo(streamLimit * 3 / 2)
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()
        }
    }

    private fun sslContext(isClient: Boolean): QuicSslContext {
        val hostKey = generateEcdsaKeyPair().first
        val connectionKey = generateEcdsaKeyPair().first
        val cert = buildCert(hostKey, connectionKey)
        val javaKey = getJavaKey(connectionKey)
        return (
            if (isClient) {
                QuicSslContextBuilder.forClient().keyManager(javaKey, null, cert)
            } else {
                QuicSslContextBuilder.forServer(javaKey, null, cert).clientAuth(ClientAuth.REQUIRE)
            }
            )
            .trustManager(Libp2pTrustManager(Optional.empty()))
            .applicationProtocols("libp2p")
            .endpointIdentificationAlgorithm(null)
            .build()
    }
}
