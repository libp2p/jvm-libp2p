package io.libp2p.core.mux

import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.mux.mplex.MplexStreamMuxer
import io.libp2p.mux.yamux.DEFAULT_ACK_BACKLOG_LIMIT
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
         * @param ackBacklogLimit the maximum amount of opened streams per connection which have not been acknowledged
         */
        @JvmStatic
        @JvmOverloads
        fun getYamux(ackBacklogLimit: Int = DEFAULT_ACK_BACKLOG_LIMIT): StreamMuxerProtocol {
            return StreamMuxerProtocol { multistreamProtocol, protocols ->
                YamuxStreamMuxer(
                    multistreamProtocol.createMultistream(
                        protocols
                    ).toStreamHandler(),
                    multistreamProtocol,
                    ackBacklogLimit
                )
            }
        }
    }
}
