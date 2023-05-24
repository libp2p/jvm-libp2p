package io.libp2p.security.tls

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multistream.MultistreamProtocolDebug
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.security.InvalidRemotePubKey
import io.libp2p.security.SecureChannelTestBase
import io.libp2p.security.logger
import io.libp2p.tools.TestChannel
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.logging.Level

val MultistreamProtocolV1: MultistreamProtocolDebug = MultistreamProtocolDebugV1()

@Tag("secure-channel")
class TlsSecureChannelTest : SecureChannelTestBase(
    ::TlsSecureChannel,
    listOf(StreamMuxerProtocol.Yamux.createMuxer(MultistreamProtocolV1, listOf()).protocolDescriptor.announceProtocols.get(0)),
    TlsSecureChannel.announce
) {
    @Test
    fun `incorrect initiator remote PeerId should throw`() {
        val (privKey1, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (privKey2, _) = generateKeyPair(KEY_TYPE.ECDSA)
        val (_, wrongPubKey) = generateKeyPair(KEY_TYPE.ECDSA)

        val protocolSelect1 = makeSelector(privKey1, muxerIds)
        val protocolSelect2 = makeSelector(privKey2, muxerIds)

        val eCh1 = makeDialChannel("#1", protocolSelect1, PeerId.fromPubKey(wrongPubKey))
        val eCh2 = makeListenChannel("#2", protocolSelect2)

        TestChannel.interConnect(eCh1, eCh2)

        Assertions.assertThatThrownBy { protocolSelect1.selectedFuture.get(10, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(InvalidRemotePubKey::class.java)
    }
}
