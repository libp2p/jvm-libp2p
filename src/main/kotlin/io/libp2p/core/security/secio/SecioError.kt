package io.libp2p.core.security.secio

/**
 * Created by Anton Nashatyrev on 14.06.2019.
 */
open class SecioError: Exception()

open class SecioHandshakeError: SecioError()

class NoCommonAlgos: SecioHandshakeError()
class InvalidRemotePubKey: SecioHandshakeError()
class InvalidSignature: SecioHandshakeError()
class InvalidNegotiationState: SecioHandshakeError()
class InvalidInitialPacket: SecioHandshakeError()

class MacMismatch: SecioError()