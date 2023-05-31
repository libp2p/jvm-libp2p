package io.libp2p.core.mux

import io.libp2p.core.multistream.NegotiatedProtocol

typealias NegotiatedStreamMuxer = NegotiatedProtocol<StreamMuxer.Session, StreamMuxer>