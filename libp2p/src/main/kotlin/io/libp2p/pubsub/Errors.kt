package io.libp2p.pubsub

import io.libp2p.core.Libp2pException

/**
 * Generic Pubsub exception
 */
open class PubsubException(message: String) : Libp2pException(message)

/**
 * Is thrown when a client sends duplicate message
 */
class MessageAlreadySeenException(message: String) : PubsubException(message)

/**
 * Throw when message validation failed
 */
class InvalidMessageException(message: String) : PubsubException(message)

/**
 * Thrown when no suitable peers found to broadcast outbound exception
 */
class NoPeersForOutboundMessageException(message: String) : PubsubException(message)
