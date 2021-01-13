package io.libp2p.core.mux

import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.mux.mplex.MplexStreamMuxer

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
    }
}
