package io.libp2p.security.noise

import io.libp2p.security.SecureChannelTest

class NoiseSecureChannelTest : SecureChannelTest(
    ::NoiseXXSecureChannel,
    NoiseXXSecureChannel.announce)
