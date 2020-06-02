package io.libp2p.core.multistream

typealias ProtocolId = String

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