package io.libp2p.security.plaintext

import io.libp2p.security.SecureChannelTest
import org.junit.jupiter.api.Tag

@Tag("secure-channel")
class PlaintextInsecureChannelTest : SecureChannelTest(
    ::PlaintextInsecureChannel,
    "/plaintext/2.0.0"
)
