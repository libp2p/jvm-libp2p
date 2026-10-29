package io.libp2p.transport.quic

import io.libp2p.core.crypto.KeyType
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.etc.types.hasCauseOfType
import io.libp2p.tools.TestLogAppender
import io.netty.handler.codec.quic.QuicException
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.config.Configurator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * A well-formed QUIC v1 Initial that the server cannot decrypt (e.g. a late retransmission for a
 * connection the server already dropped, or scanner traffic) makes quiche fail with
 * QUICHE_ERR_CRYPTO_FAIL before any handshake progress. That is per-packet noise, not a local
 * fault, so it must not be logged at ERROR.
 */
class QuicHandshakeErrorLoggingTest {

    @Test
    fun `undecryptable inbound Initial is logged at debug, not error`() {
        val loggerName = QuicConnectionExceptionHandler::class.java.name
        val previousLevel = LogManager.getLogger(loggerName).level
        Configurator.setLevel(loggerName, Level.DEBUG)

        val port = Random().nextInt(20_000) + 10_000
        val host = HostBuilder()
            .keyType(KeyType.ED25519)
            .secureTransport(QuicTransport::ECDSA)
            .listen("/ip4/127.0.0.1/udp/$port/quic-v1")
            .build()
        host.start().get(5, TimeUnit.SECONDS)
        try {
            TestLogAppender().install().use { logs ->
                DatagramSocket().use { socket ->
                    val pkt = undecryptableInitial()
                    socket.send(DatagramPacket(pkt, pkt.size, InetAddress.getLoopbackAddress(), port))
                }

                val quicFailure = awaitEvent(logs) {
                    it.loggerName == loggerName && it.thrown?.hasCauseOfType(QuicException::class) == true
                }

                assertThat(quicFailure).describedAs("QUIC failure was logged").isNotNull
                assertThat(quicFailure!!.level).isEqualTo(Level.DEBUG)
                assertThat(quicFailure.thrown.toString()).contains("QUICHE_ERR_CRYPTO_FAIL")
                assertThat(logs.logs.filter { it.level == Level.ERROR }).isEmpty()
            }
        } finally {
            host.stop().get(5, TimeUnit.SECONDS)
            Configurator.setLevel(loggerName, previousLevel)
        }
    }

    private fun awaitEvent(logs: TestLogAppender, predicate: (LogEvent) -> Boolean): LogEvent? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            logs.logs.firstOrNull(predicate)?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    /**
     * A syntactically valid QUIC v1 Initial (RFC 9000 §17.2.2) with no token and an all-zero
     * payload. Servers drop Initials smaller than 1200 bytes, so the datagram is padded to that.
     */
    private fun undecryptableInitial(): ByteArray {
        val pkt = ByteArray(1200)
        val headerLen = 1 + 4 + 1 + 8 + 1 + 8 + 1 + 2
        ByteBuffer.wrap(pkt)
            .put(0xC3.toByte()) // long header, type = Initial, 4-byte packet number
            .putInt(1) // version 1
            .put(8).put(ByteArray(8) { 1 }) // DCID
            .put(8).put(ByteArray(8) { 2 }) // SCID
            .put(0) // token length
            .putShort((0x4000 or (pkt.size - headerLen)).toShort()) // 2-byte varint: remaining length
        return pkt
    }
}
