package io.libp2p.pubsub

import io.libp2p.core.Libp2pException

open class PubsubException(message: String) : Libp2pException(message)

class MessageAlreadySeenException(message: String) : PubsubException(message)
