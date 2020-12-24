package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.netty.channel.ChannelHandler
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
    var beforeSecureHandler: ChannelHandler? = null
    var afterSecureHandler: ChannelHandler? = null
    var beforeMuxHandler: ChannelHandler? = null
    var afterMuxHandler: ChannelHandler? = null

    open fun establishSecureChannel(connection: Connection): CompletableFuture<SecureChannel.Session> {
        return establish(
            secureMultistream,
            connection,
            secureChannels,
            beforeSecureHandler,
            afterSecureHandler
        )
    } // establishSecureChannel

    open fun establishMuxer(connection: Connection): CompletableFuture<StreamMuxer.Session> {
        return establish(
            muxerMultistream,
            connection,
            muxers,
            beforeMuxHandler,
            afterMuxHandler
        )
    } // establishMuxer

    private fun <T : ProtocolBinding<R>, R> establish(
        mulristreamProto: MultistreamProtocol,
        connection: Connection,
        channels: List<T>,
        beforeHandler: ChannelHandler?,
        afterHandler: ChannelHandler?
    ): CompletableFuture<R> {
        beforeHandler?.also { connection.pushHandler(it) }

        val multistream = mulristreamProto.create(channels)

        return multistream.initChannel(connection)
            .thenApply {
                afterHandler?.also { connection.pushHandler(it) }
                it
            }
    } // establish
} // ConnectionUpgrader
