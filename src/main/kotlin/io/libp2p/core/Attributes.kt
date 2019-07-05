package io.libp2p.core

import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.netty.util.AttributeKey

val MUXER_SESSION = AttributeKey.newInstance<StreamMuxer.Session>("LIBP2P_MUXER_SESSION")!!
val SECURE_SESSION = AttributeKey.newInstance<SecureChannel.Session>("LIBP2P_SECURE_SESSION")!!
