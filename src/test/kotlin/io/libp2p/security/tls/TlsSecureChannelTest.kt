package io.libp2p.security.tls

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.security.InvalidRemotePubKey
import io.libp2p.security.SecureChannelTestBase
import io.libp2p.security.logger
import io.libp2p.tools.TestChannel
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.logging.Level

@Tag("secure-channel")
class TlsSecureChannelTest : SecureChannelTestBase(
    ::TlsSecureChannel,
    TlsSecureChannel.announce
) {
    @Test
    fun `incorrect initiator remote PeerId should throw`() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (_, wrongPubKey) = generateKeyPair(KEY_TYPE.ECDSA)

        val protocolSelect1 = makeSelector(privKey1)
        val protocolSelect2 = makeSelector(privKey2)

        val eCh1 = makeDialChannel("#1", protocolSelect1, PeerId.fromPubKey(wrongPubKey))
        val eCh2 = makeListenChannel("#2", protocolSelect2)

        logger.log(Level.FINE, "Connecting channels...")
        TestChannel.interConnect(eCh1, eCh2)

        Assertions.assertThatThrownBy { protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(InvalidRemotePubKey::class.java)
    }
}
