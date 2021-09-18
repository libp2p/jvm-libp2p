package io.libp2p.security.noise

import com.southernstorm.noise.protocol.CipherState
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.security.SecureChannel

class NoiseSecureChannelSession(
    localId: PeerId,
    remoteId: PeerId,
    remotePubKey: PubKey,
    val aliceCipher: CipherState,
    val bobCipher: CipherState
) : SecureChannel.Session(localId, remoteId, remotePubKey)
