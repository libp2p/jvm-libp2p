package io.libp2p.security.plaintext

import io.libp2p.security.SecureChannelTestBase
import org.junit.jupiter.api.Tag

@Tag("secure-channel")
class PlaintextInsecureChannelTest : SecureChannelTestBase(
    ::PlaintextInsecureChannel,
    listOf(),
    "/plaintext/2.0.0"
)
