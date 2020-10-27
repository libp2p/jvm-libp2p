package io.libp2p.security

open class SecureChannelError : Exception {
    constructor() : super()
    constructor(message: String, cause: Throwable) : super(message, cause)
}

open class SecureHandshakeError : SecureChannelError()

class InvalidRemotePubKey : SecureHandshakeError()
class InvalidInitialPacket : SecureHandshakeError()

class CantDecryptInboundException(message: String, cause: Throwable) : SecureChannelError(message, cause)