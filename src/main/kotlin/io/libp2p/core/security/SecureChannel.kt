package io.libp2p.core.security

import io.libp2p.core.PeerId
import io.libp2p.core.protocol.ProtocolBinding
import java.security.PublicKey

/**
 * The SecureChannel interface is implemented by all security channels, such as SecIO, TLS 1.3, Noise, and so on.
 */
interface SecureChannel : ProtocolBinding<SecureChannel.Session> {
    interface Session {
        /**
         * The peer ID of the local peer.
         */
        val localId: PeerId

        /**
         * The peer ID of the remote peer.
         */
        val remoteId: PeerId

        /**
         * The public key of the
         */
        val remotePubKey: PublicKey
    }
}