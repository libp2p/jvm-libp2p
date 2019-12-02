package io.libp2p.security.secio

import io.libp2p.security.SecureChannelTest
import org.junit.jupiter.api.Tag

@Tag("secure-channel")
class SecIoSecureChannelTest : SecureChannelTest(
    ::SecIoSecureChannel,
    "/secio/1.0.0"
)
