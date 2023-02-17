package io.libp2p.core.security

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.multistream.ProtocolBinding

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
         * The public key of the
         */
        val remotePubKey: PubKey
    )
}
