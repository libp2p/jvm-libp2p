package io.libp2p.core.multistream

/**
 * A matcher that evaluates whether a given protocol activates based on its protocol ID.
 */
interface ProtocolMatcher {

    /**
     * Evaluates this matcher against a proposed protocol ID.
     */
    fun matches(proposed: ProtocolId): Boolean

    companion object {
        fun strict(protocol: ProtocolId) = object : ProtocolMatcher {
            override fun matches(proposed: ProtocolId) = protocol == proposed
        }
        fun prefix(protocolPrefix: String) = object : ProtocolMatcher {
            override fun matches(proposed: ProtocolId) = proposed.startsWith(protocolPrefix)
        }
        fun list(protocols: Collection<String>) = object : ProtocolMatcher {
            override fun matches(proposed: ProtocolId) = proposed in protocols
        }
    }
}
