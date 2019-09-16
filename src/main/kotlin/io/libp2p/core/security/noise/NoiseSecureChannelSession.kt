package io.libp2p.core.security.noise

import com.southernstorm.noise.protocol.CipherState
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.PubKey
import io.libp2p.core.security.SecureChannel

class NoiseSecureChannelSession(localId: PeerId, remoteId: PeerId, remotePubKey: PubKey, aliceCipher: CipherState, bobCipher: CipherState): SecureChannel.Session(localId, remoteId, remotePubKey) {
    val aliceCipher = aliceCipher
    val bobCipher = bobCipher
}
