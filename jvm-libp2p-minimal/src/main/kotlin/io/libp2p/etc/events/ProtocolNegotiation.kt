package io.libp2p.etc.events

/**
 * The user event emitted when protocol negotiation succeeds.
 */
data class ProtocolNegotiationSucceeded(val proto: String)

/**
 * The user event emitted when protocol negotiation fails.
 */
data class ProtocolNegotiationFailed(val attempted: List<String>)
