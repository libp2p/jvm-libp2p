package io.libp2p.core.security.noise

class NoiseSecureChannelSession(localId: PeerId, remoteId: PeerId, remotePubKey: PubKey, aliceCipher: CipherState, bobCipher: CipherState): SecureChannel.Session(localId, remoteId, remotePubKey) {
    val aliceCipher = aliceCipher
    val bobCipher = bobCipher
}
