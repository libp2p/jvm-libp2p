package io.libp2p.security

import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestLogAppender
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

abstract class CipherSecureChannelTest(secureChannelCtor: SecureChannelCtor, announce: String) :
    SecureChannelTest(secureChannelCtor, announce) {

    @Test
    fun `test that on malformed message from remote the connection closes and no log noise`() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)

        val protocolSelect1 = makeSelector(privKey1)
        val protocolSelect2 = makeSelector(privKey2)

        val eCh1 = makeChannel("#1", true, protocolSelect1)
        val eCh2 = makeChannel("#2", false, protocolSelect2)

        logger.debug("Connecting channels...")
        TestChannel.interConnect(eCh1, eCh2)

        logger.debug("Waiting for negotiation to complete...")
        protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS)
        protocolSelect2.selectedFuture.get(10, TimeUnit.SECONDS)
        logger.debug("Secured!")

        TestLogAppender().install().use { testLogAppender ->
            Assertions.assertThatCode {
                // writing invalid cipher data
                eCh1.writeInbound(Unpooled.wrappedBuffer(ByteArray(128)))
            }.doesNotThrowAnyException()

            Assertions.assertThat(eCh1.isOpen).isFalse()
            org.junit.jupiter.api.Assertions.assertFalse(testLogAppender.hasAnyWarns())
        }
    }
}
