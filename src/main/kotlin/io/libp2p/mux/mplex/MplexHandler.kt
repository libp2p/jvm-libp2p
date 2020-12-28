package io.libp2p.mux.mplex

import io.libp2p.core.StreamHandler
import io.libp2p.core.multistream.MultistreamProtocol
import io.libp2p.core.mux.StreamMuxer
import io.libp2p.mux.MuxHandler
import java.util.concurrent.CompletableFuture

open class MplexHandler(
    override val multistreamProtocol: MultistreamProtocol,
    ready: CompletableFuture<StreamMuxer.Session>?,
    inboundStreamHandler: StreamHandler<*>
) : MuxHandler(ready, inboundStreamHandler)
