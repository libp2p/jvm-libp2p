package io.libp2p.etc

import io.libp2p.core.Connection
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.CompletableFuture

val MUXER_SESSION = AttributeKey.newInstance<StreamMuxer.Session>("LIBP2P_MUXER_SESSION")!!
val SECURE_SESSION = AttributeKey.newInstance<SecureChannel.Session>("LIBP2P_SECURE_SESSION")!!
val IS_INITIATOR = AttributeKey.newInstance<Boolean>("LIBP2P_IS_INITIATOR")!!
val STREAM = AttributeKey.newInstance<Stream>("LIBP2P_STREAM")!!
val CONNECTION = AttributeKey.newInstance<Connection>("LIBP2P_CONNECTION")!!
val PROTOCOL = AttributeKey.newInstance<CompletableFuture<String>>("LIBP2P_PROTOCOL")!!
val REMOTE_PEER_ID = AttributeKey.newInstance<PeerId>("LIBP2P_REMOTE_PEER_ID")!!
val TRANSPORT = AttributeKey.newInstance<Transport>("LIBP2P_TRANSPORT")!!

fun Channel.getP2PChannel() = if (hasAttr(CONNECTION)) attr(CONNECTION).get() else attr(STREAM).get()