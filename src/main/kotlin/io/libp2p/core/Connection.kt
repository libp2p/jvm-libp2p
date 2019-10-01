package io.libp2p.core

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.libp2p.core.transport.Transport

interface Connection : P2PChannel {
    /**
     * Returns the [io.libp2p.core.mux.StreamMuxer.Session] which is capable of creating
     * new [Stream]s
     */
    val muxerSession: StreamMuxer.Session

    /**
     * Returns the [io.libp2p.core.security.SecureChannel.Session] which contains
     * security attributes of this connection
     */
    val secureSession: SecureChannel.Session

    /**
     * Returns the [io.libp2p.core.transport.Transport] instance behind this [Connection]
     */
    val transport: Transport

    /**
     * Returns the local [Multiaddr] of this [Connection]
     */
    fun localAddress(): Multiaddr
    /**
     * Returns the remote [Multiaddr] of this [Connection]
     */
    fun remoteAddress(): Multiaddr
}
