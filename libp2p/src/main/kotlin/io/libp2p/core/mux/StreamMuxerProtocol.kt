package io.libp2p.core.mux

import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.mux.yamux.DEFAULT_ACK_BACKLOG_LIMIT
import io.libp2p.mux.yamux.DEFAULT_MAX_BUFFERED_CONNECTION_WRITES
import io.libp2p.mux.yamux.YamuxStreamMuxer

fun interface StreamMuxerProtocol {

    fun createMuxer(multistreamProtocol: MultistreamProtocol, protocols: List<ProtocolBinding<*>>): StreamMuxer

    companion object {
        @JvmStatic
        val Mplex = StreamMuxerProtocol { multistreamProtocol, protocols ->
            MplexStreamMuxer(
                multistreamProtocol.createMultistream(
                    protocols
                ).toStreamHandler(),
                multistreamProtocol
            )
        }

        /**
         * @param maxBufferedConnectionWrites the maximum amount of bytes which may be buffered (pending)
         * across the write buffers of all streams of a single connection. When the limit is overflowed,
         * the stream attempting to write is reset
         * @param ackBacklogLimit the maximum amount of opened streams per connection which have not been acknowledged
         */
        @JvmStatic
        @JvmOverloads
        fun getYamux(
            maxBufferedConnectionWrites: Int = DEFAULT_MAX_BUFFERED_CONNECTION_WRITES,
            ackBacklogLimit: Int = DEFAULT_ACK_BACKLOG_LIMIT
        ): StreamMuxerProtocol {
            return StreamMuxerProtocol { multistreamProtocol, protocols ->
                YamuxStreamMuxer(
                    multistreamProtocol.createMultistream(
                        protocols
                    ).toStreamHandler(),
                    multistreamProtocol,
                    maxBufferedConnectionWrites,
                    ackBacklogLimit
                )
            }
        }
    }
}
