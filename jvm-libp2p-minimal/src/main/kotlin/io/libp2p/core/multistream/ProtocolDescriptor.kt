package io.libp2p.core.multistream

/**
 * String describing a specific protocol version, like e.g. `/chat/3.1.0` or `/calc/1.0.0/plus`
 * Though there is no any restrictions on the string format
 */
typealias ProtocolId = String

/**
 * Describes which protocols are accepted on inbound negotiation and
 * which protocols are announced on outbound negotiation
 *
 * A descriptor may relate to a single protocol in this case the [announceProtocols] list
 * would normally contain protocol versions starting from the newest version (most preferable)
 * and ending with the oldest supported version.
 * For example
 * ```
 * /chat/3.1.0
 * /chat/3.0.0
 * /chat/2.0.0
 * ```
 *
 * A descriptor may also represent a family of protocols like RPC method bindings then
 * [announceProtocols] list should be empty
 * For example
 * ```
 * /calc/1.0.0/plus
 * /calc/1.0.0/minus
 * ```
 * In this case the announced protocol should be specified explicitly on each [Stream] initiation
 */
class ProtocolDescriptor(
    val announceProtocols: List<ProtocolId>,
    val protocolMatcher: ProtocolMatcher
) {

    constructor(announce: ProtocolId) : this(listOf(announce), ProtocolMatcher.strict(announce))
    constructor(vararg protocols: ProtocolId) : this(listOf(*protocols), ProtocolMatcher.list(listOf(*protocols)))
    constructor(protocols: List<ProtocolId>) : this(protocols, ProtocolMatcher.list(protocols))
    constructor(announce: ProtocolId, matcher: ProtocolMatcher) : this(listOf(announce), matcher)
    constructor(matcher: ProtocolMatcher) : this(listOf(), matcher)

    fun matchesAny(protocols: Collection<ProtocolId>) = protocols.any { protocolMatcher.matches(it) }
}
