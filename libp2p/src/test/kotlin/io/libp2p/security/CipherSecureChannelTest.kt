package io.libp2p.security

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestLogAppender
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.SECONDS

abstract class CipherSecureChannelTest(secureChannelCtor: SecureChannelCtor, muxerIds: List<String>, announce: String) :
    SecureChannelTestBase(secureChannelCtor, muxerIds, announce) {

    @Test
    fun `incorrect initiator remote PeerId should throw`() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (_, wrongPubKey) = generateKeyPair(KEY_TYPE.ECDSA)

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
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, pubKey2) = generateKeyPair(KEY_TYPE.ECDSA)

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
