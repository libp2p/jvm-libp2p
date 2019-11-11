package io.libp2p.security

open class SecureChannelError : Exception()

open class SecureHandshakeError : SecureChannelError()

class InvalidRemotePubKey : SecureHandshakeError()
class InvalidInitialPacket : SecureHandshakeError()
