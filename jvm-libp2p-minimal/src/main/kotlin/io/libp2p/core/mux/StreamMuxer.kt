package io.libp2p.core.mux

import io.libp2p.core.ChannelVisitor
import io.libp2p.core.Connection
import io.libp2p.core.StreamHandler
import io.libp2p.core.StreamPromise
import io.libp2p.core.multistream.ProtocolBinding

/**
 * Performs stream multiplexing of an abstract channel
 * While any channel can be multiplexed in theory, libp2p basically involves [StreamMuxer] to upgrade
 * a [Transport] to have multiplexing capability if it doesn't support it out of the box.
 *
 * The example of multiplex protocol is [Mplex][https://github.com/libp2p/specs/tree/master/mplex]
 *
 * Multiplexer spawns child channels referred as [io.libp2p.core.Stream]s
 * A new [Stream] creation is initiated either actively by the local peer via [StreamMuxer.Session.createStream]
 * or passively by a remote side.
 *
 * Multiplexers basically support half-closed streams. Closing a stream closes it for writing and
 * closes the remote end for reading but allows writing in the other direction.
 * Client protocol implementations may perform closing for write by calling [io.libp2p.core.Stream.nettyChannel.disconnect]
 *
 * The stream can also be forcibly closed by a single side (which is called _RESET_).
 * Client protocol implementations may perform resetting a stream via [io.libp2p.core.Stream.nettyChannel.close]
 */
interface StreamMuxer : ProtocolBinding<StreamMuxer.Session> {

    /**
     * The Multiplexer controller which is capable of opening new Streams
     */
    interface Session {
        /**
         * Initiates a new Stream creation.
         * The passed [streamHandler] is basically a [io.libp2p.core.multistream.Multistream] _initiator_
         * for a client protocol which yields a controller of type [T] on its initialization
         *
         * The returned [StreamHandler] contains both a future Stream for lowlevel Stream manipulations
         * and future Controller for the client protocol manipulations
         */
        fun <T> createStream(protocols: List<ProtocolBinding<T>>): StreamPromise<T>

        @JvmDefault
        fun <T> createStream(protocol: ProtocolBinding<T>): StreamPromise<T> = createStream(listOf(protocol))
    }
}

/**
 * Extra interface which could optionally be implemented by [StreamMuxer]s for debugging/logging
 * purposes
 */
interface StreamMuxerDebug {
    /**
     * The Netty handler (if not [null]) which is inserted right after multiplexer _frames_ decoder/encoder
     * Normally this is an instance of [io.netty.handler.logging.LoggingHandler] for dumping muxer frames
     */
    var muxFramesDebugHandler: ChannelVisitor<Connection>?
}
