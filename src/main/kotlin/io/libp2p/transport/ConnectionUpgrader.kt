package io.libp2p.transport

import io.libp2p.core.Connection
import io.libp2p.core.multistream.Multistream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.core.security.SecureChannel
import io.netty.channel.ChannelHandler
import java.util.concurrent.CompletableFuture

/**
 * ConnectionUpgrader is a utility class that Transports can use to shim secure channels and muxers when those
 * capabilities are not provided natively by the transport.
 */
class ConnectionUpgrader(
    private val secureChannels: List<SecureChannel>,
    private val muxers: List<StreamMuxer>
) {
    var beforeSecureHandler: ChannelHandler? = null
    var afterSecureHandler: ChannelHandler? = null
    var beforeMuxHandler: ChannelHandler? = null
    var afterMuxHandler: ChannelHandler? = null

    fun establishSecureChannel(connection: Connection): CompletableFuture<SecureChannel.Session> {
        return establish(
            connection,
            secureChannels,
            beforeSecureHandler,
            afterSecureHandler
        )
    } // establishSecureChannel

    fun establishMuxer(connection: Connection): CompletableFuture<StreamMuxer.Session> {
        return establish(
            connection,
            muxers,
            beforeMuxHandler,
            afterMuxHandler
        )
    } // establishMuxer

    private fun <T : ProtocolBinding<out R>, R> establish(
        connection: Connection,
        channels: List<T>,
        beforeHandler: ChannelHandler?,
        afterHandler: ChannelHandler?
    ) : CompletableFuture<R> {
        beforeHandler?.also { connection.pushHandler(it) }

        val multistream = Multistream.create(channels)

        return multistream.initChannel(connection)
            .thenApply {
                afterHandler?.also { connection.pushHandler(it) }
                it
            }
    } // establish
} // ConnectionUpgrader
