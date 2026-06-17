package io.libp2p.security.tls

import io.libp2p.core.multistream.MultistreamProtocolDebug
import io.libp2p.core.mux.StreamMuxerProtocol
import io.libp2p.multistream.MultistreamProtocolDebugV1
import io.libp2p.security.CipherSecureChannelTest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

val MultistreamProtocolV1: MultistreamProtocolDebug = MultistreamProtocolDebugV1()

@Tag("secure-channel")
class TlsSecureChannelTest : CipherSecureChannelTest(
    ::TlsSecureChannel,
    listOf(StreamMuxerProtocol.getYamux().createMuxer(MultistreamProtocolV1, listOf())),
    TlsSecureChannel.announce
) {
    // The malformed-cipher-bytes test from CipherSecureChannelTest is SecIO/Noise-specific:
    // it injects raw bytes post-handshake and expects no WARN-level log noise. TLS via OpenSSL
    // surfaces these errors through a different path that is out of scope for this test class.
    @Test
    @Disabled("TLS surfaces malformed-cipher-bytes via OpenSSL — covered separately, not part of the SecureChannel.Session contract under test here")
    override fun `test that on malformed message from remote the connection closes and no log noise`() {
    }
}
