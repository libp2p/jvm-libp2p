package io.libp2p.core.multistream

interface ProtocolMatcher {
    fun matches(proposed: String): Boolean

    companion object {
        fun strict(protocol: String) = object : ProtocolMatcher {
            override fun matches(proposed: String) = protocol == proposed
        }
        fun prefix(protocolPrefix: String) = object : ProtocolMatcher {
            override fun matches(proposed: String) = proposed.startsWith(protocolPrefix)
        }
        fun list(protocols: Collection<String>) = object : ProtocolMatcher {
            override fun matches(proposed: String) = proposed in protocols
        }
    }
}
