package io.libp2p.security.secio

import io.libp2p.security.SecureChannelTest

class SecIoSecureChannelTest : SecureChannelTest(
    ::SecIoSecureChannel,
    "/secio/1.0.0"
)
