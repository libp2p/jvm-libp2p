package io.libp2p.security

open class SecureChannelError : Exception {
    constructor() : super()
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(message: String) : super(message)
}

open class SecureHandshakeError : SecureChannelError()

class InvalidRemotePubKey : SecureHandshakeError()
class InvalidInitialPacket : SecureHandshakeError()

open class CantDecryptInboundException : SecureChannelError {
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(message: String) : super(message)
}

class InvalidMacException : CantDecryptInboundException("Invalid MAC")
