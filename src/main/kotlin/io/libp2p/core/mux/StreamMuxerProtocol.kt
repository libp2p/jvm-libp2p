package io.libp2p.core.mux

import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.mux.mplex.MplexStreamMuxer

val MplexProtocol = object : StreamMuxerProtocol {

    override fun createMuxer(
        multistreamProtocol: MultistreamProtocol,
        protocols: List<ProtocolBinding<*>>
    ): StreamMuxer {
        return MplexStreamMuxer(multistreamProtocol.create(protocols).toStreamHandler(), multistreamProtocol)
    }
}

interface StreamMuxerProtocol {

    fun createMuxer(multistreamProtocol: MultistreamProtocol, protocols: List<ProtocolBinding<*>>): StreamMuxer
}