package io.libp2p.simulate.stream

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.security.SecureChannel
import io.libp2p.simulate.util.DummyChannel
import io.libp2p.simulate.util.NullTransport
import io.libp2p.transport.implementation.ConnectionOverNetty

class Libp2pConnectionImpl(
    val remoteAddr:
        Multiaddr,
    isInitiator: Boolean,
    localPubkey: PubKey,
    remotePubkey: PubKey,
) : ConnectionOverNetty(
    DummyChannel(),
    NullTransport(),
    isInitiator
) {
    init {
        setSecureSession(
            SecureChannel.Session(
                PeerId.fromPubKey(localPubkey),
                PeerId.fromPubKey(remotePubkey),
                remotePubkey,
                ""
            )
        )
    }

    override fun remoteAddress() = remoteAddr
}
