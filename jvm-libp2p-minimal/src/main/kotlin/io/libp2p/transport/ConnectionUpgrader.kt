package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import java.util.concurrent.CompletableFuture

/**
 * ConnectionUpgrader is a utility class that Transports can use to shim secure channels and muxers when those
 * capabilities are not provided natively by the transport.
 */
open class ConnectionUpgrader(
    private val secureMultistream: MultistreamProtocol,
    private val secureChannels: List<SecureChannel>,
    private val muxerMultistream: MultistreamProtocol,
    private val muxers: List<StreamMuxer>,
) {
    open fun establishSecureChannel(connection: Connection): CompletableFuture<SecureChannel.Session> {
        return establish(
            secureMultistream,
            connection,
            secureChannels
        )
    } // establishSecureChannel

    open fun establishMuxer(connection: Connection): CompletableFuture<StreamMuxer.Session> {
        return establish(
            muxerMultistream,
            connection,
            muxers
        )
    } // establishMuxer

    private fun <T : ProtocolBinding<R>, R> establish(
        multistreamProtocol: MultistreamProtocol,
        connection: Connection,
        channels: List<T>
    ): CompletableFuture<R> {
        val multistream = multistreamProtocol.createMultistream(channels)
        return multistream.initChannel(connection)
    } // establish
} // ConnectionUpgrader
