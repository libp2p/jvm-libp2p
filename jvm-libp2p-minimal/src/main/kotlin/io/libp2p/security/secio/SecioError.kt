package io.libp2p.security.secio

import io.libp2p.security.SecureHandshakeError

class NoCommonAlgos : SecureHandshakeError()
class InvalidSignature : SecureHandshakeError()
class InvalidNegotiationState : SecureHandshakeError()
class SelfConnecting : SecureHandshakeError()

class MacMismatch : SecureHandshakeError()
