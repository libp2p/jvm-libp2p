package io.libp2p.transport.quic

import java.time.Duration

data class QuicConfig(
    val connectTimeout: Duration = Duration.ofSeconds(15),
    val idleTimeout: Duration = Duration.ofSeconds(30),
    val maxConnectionData: Long = 15L * 1024 * 1024,
    val maxStreamDataLocal: Long = 10L * 1024 * 1024,
    val maxStreamDataRemote: Long = 10L * 1024 * 1024,
    val maxStreamsBidirectional: Long = 256,
)
