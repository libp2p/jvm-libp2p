package io.libp2p.security.secio

import io.libp2p.security.CipherSecureChannelTest
import org.junit.jupiter.api.Tag

@Tag("secure-channel")
class SecIoSecureChannelTest : CipherSecureChannelTest(
    ::SecIoSecureChannel,
    listOf(),
    "/secio/1.0.0"
)
