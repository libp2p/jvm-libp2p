package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.etc.MUXER_SESSION
import io.libp2p.etc.SECURE_SESSION
import io.libp2p.etc.TRANSPORT
import io.netty.channel.Channel

/**
 * A Connection is a high-level wrapper around a Netty Channel representing the conduit to a peer.
 *
 * It exposes libp2p components and semantics via methods and properties.
 */
class Connection(ch: Channel) : P2PAbstractChannel(ch) {
    /**
     * Returns the [io.libp2p.core.mux.StreamMuxer.Session] which is capable of creating
     * new [Stream]s
     */
    val muxerSession by lazy { ch.attr(MUXER_SESSION).get() }
    /**
     * Returns the [io.libp2p.core.security.SecureChannel.Session] which contains
     * security attributes of this connection
     */
    val secureSession by lazy { ch.attr(SECURE_SESSION).get() }

    /**
     * Returns the [io.libp2p.core.transport.Transport] instance behind this [Connection]
     */
    val transport by lazy { ch.attr(TRANSPORT).get() }

    /**
     * Returns the remote [Multiaddr] of this [Connection]
     */
    fun remoteAddress() = transport.remoteAddress(this)

    /**
     * Returns the local [Multiaddr] of this [Connection]
     */
    fun localAddress() = transport.localAddress(this)
}
