package io.libp2p.etc

import io.libp2p.core.Connection
import io.libp2p.core.P2PChannel
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.netty.channel.Channel
import io.netty.util.AttributeKey
import java.util.concurrent.CompletableFuture

val STREAM = AttributeKey.newInstance<Stream>("LIBP2P_STREAM")!!
val CONNECTION = AttributeKey.newInstance<Connection>("LIBP2P_CONNECTION")!!
val PROTOCOL = AttributeKey.newInstance<CompletableFuture<String>>("LIBP2P_PROTOCOL")!!
val REMOTE_PEER_ID = AttributeKey.newInstance<PeerId>("LIBP2P_REMOTE_PEER_ID")!!

fun Channel.getP2PChannel(): P2PChannel = if (hasAttr(CONNECTION)) attr(CONNECTION).get() else attr(STREAM).get()
