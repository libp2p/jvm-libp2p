package io.libp2p.etc.events

import io.libp2p.core.security.SecureChannel

data class SecureChannelInitialized(val session: SecureChannel.Session)

data class SecureChannelFailed(val exception: Throwable)
