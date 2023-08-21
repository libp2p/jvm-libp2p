package io.libp2p.core.security

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.mux.NegotiatedStreamMuxer

/**
 * The SecureChannel interface is implemented by all security channels, such as SecIO, TLS 1.3, Noise, and so on.
 */
interface SecureChannel : ProtocolBinding<SecureChannel.Session> {

    open class Session(
        /**
         * The peer ID of the local peer.
         */
        val localId: PeerId,

        /**
         * The peer ID of the remote peer.
         */
        val remoteId: PeerId,

        /**
         * The public key of the remote peer.
         */
        val remotePubKey: PubKey,

        /**
         * Contains muxer if security protocol supports
         * [Early Multiplexer Negotiation](https://docs.libp2p.io/concepts/multiplex/early-negotiation/)
         * and the protocol was successfully negotiated. Else contains `null`
         */
        val earlyMuxer: NegotiatedStreamMuxer?
    )
}
