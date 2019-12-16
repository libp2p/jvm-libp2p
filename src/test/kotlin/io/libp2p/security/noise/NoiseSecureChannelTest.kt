package io.libp2p.security.noise

import io.libp2p.security.SecureChannelTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable

@DisabledIfEnvironmentVariable(named = "TRAVIS", matches = "true")
@Tag("secure-channel")
class NoiseSecureChannelTest : SecureChannelTest(
    ::NoiseXXSecureChannel,
        "/noise/Noise_XX_25519_ChaChaPoly_SHA256/0.1.0")
