package io.libp2p.security

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestLogAppender
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS

abstract class CipherSecureChannelTest(secureChannelCtor: SecureChannelCtor, muxers: List<StreamMuxer>, announce: String) :
    SecureChannelTestBase(secureChannelCtor, muxers, announce) {

    @Test
    fun `verify secure session`() {
        val (privKey1, pubKey1) = generateKeyPair(KeyType.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KeyType.ECDSA)

        val protocolSelect1 = makeSelector(privKey1, muxerIds)
        val protocolSelect2 = makeSelector(privKey2, muxerIds)

        val eCh1 = makeDialChannel("#1", protocolSelect1, PeerId.fromPubKey(pubKey2))
        val eCh2 = makeListenChannel("#2", protocolSelect2)

        logger.debug("Connecting channels...")
        val connection = TestChannel.interConnect(eCh1, eCh2)

        val secSession1 = protocolSelect1.selectedFuture.join()
        assertThat(secSession1.localId).isEqualTo(PeerId.fromPubKey(pubKey1))
        assertThat(secSession1.remoteId).isEqualTo(PeerId.fromPubKey(pubKey2))
        assertThat(secSession1.remotePubKey).isEqualTo(pubKey2)

        val secSession2 = protocolSelect2.selectedFuture.join()
        assertThat(secSession2.localId).isEqualTo(PeerId.fromPubKey(pubKey2))
        assertThat(secSession2.remoteId).isEqualTo(PeerId.fromPubKey(pubKey1))
        assertThat(secSession2.remotePubKey).isEqualTo(pubKey1)

        logger.debug("Connection made: $connection")
    }

    @Test
    fun `incorrect initiator remote PeerId should throw`() {
        val (privKey1, _) = generateKeyPair(KeyType.ECDSA)
        val (privKey2, _) = generateKeyPair(KeyType.ECDSA)
        val (_, wrongPubKey) = generateKeyPair(KeyType.ECDSA)

        val protocolSelect1 = makeSelector(privKey1, muxerIds)
        val protocolSelect2 = makeSelector(privKey2, muxerIds)

        val eCh1 = makeDialChannel("#1", protocolSelect1, PeerId.fromPubKey(wrongPubKey))
        val eCh2 = makeListenChannel("#2", protocolSelect2)

        logger.debug("Connecting channels...")
        TestChannel.interConnect(eCh1, eCh2)

        assertThatThrownBy { protocolSelect1.selectedFuture.get(10, SECONDS) }
            .hasCauseInstanceOf(InvalidRemotePubKey::class.java)
    }

    @Test
    fun `test that on malformed message from remote the connection closes and no log noise`() {
        val (privKey1, _) = generateKeyPair(KeyType.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KeyType.ECDSA)

        val protocolSelect1 = makeSelector(privKey1, muxerIds)
        val protocolSelect2 = makeSelector(privKey2, muxerIds)

        val eCh1 = makeDialChannel("#1", protocolSelect1, PeerId.fromPubKey(pubKey2))
        val eCh2 = makeListenChannel("#2", protocolSelect2)

        logger.debug("Connecting channels...")
        TestChannel.interConnect(eCh1, eCh2)

        logger.debug("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, SECONDS)
        protocolSelect2.selectedFuture.get(10, SECONDS)
        logger.debug("Secured!")

        TestLogAppender().install().use { testLogAppender ->
            Assertions.assertThatCode {
                // writing invalid cipher data
                eCh1.writeInbound(Unpooled.wrappedBuffer(ByteArray(128)))
            }.doesNotThrowAnyException()

            assertThat(eCh1.isOpen).isFalse()
            assertThat(testLogAppender.hasAnyWarns()).isFalse()
        }
    }
}
