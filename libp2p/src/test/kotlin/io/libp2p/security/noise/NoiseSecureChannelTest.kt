package io.libp2p.security.noise

import io.libp2p.security.CipherSecureChannelTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable

@DisabledIfEnvironmentVariable(named = "TRAVIS", matches = "true")
@Tag("secure-channel")
class NoiseSecureChannelTest : CipherSecureChannelTest(
    ::NoiseXXSecureChannel,
    listOf(),
    NoiseXXSecureChannel.announce
)
