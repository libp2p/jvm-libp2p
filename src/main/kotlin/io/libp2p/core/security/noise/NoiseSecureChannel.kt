package io.libp2p.core.security.noise

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.protocol.ProtocolBindingInitializer
import io.libp2p.core.protocol.ProtocolMatcher
import io.libp2p.core.security.SecureChannel
import java.security.PublicKey

class NoiseSecureChannel(val localKey: PrivKey, val remotePeerId: PeerId? = null) :
        SecureChannel {
    override val announce: String
        get() = TODO("not implemented") // To change initializer of created properties use File | Settings | File Templates.
    override val matcher: ProtocolMatcher
        get() = TODO("not implemented") // To change initializer of created properties use File | Settings | File Templates.

    override fun initializer(): ProtocolBindingInitializer<SecureChannel.Session> {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    private fun a() {
    }
}

/**
 * SecioSession exposes the identity and public security material of the other party as authenticated by SecIO.
 */
class NoiseSession(
    override val localId: PeerId,
    override val remoteId: PeerId,
    override val remotePubKey: PublicKey
) : SecureChannel.Session