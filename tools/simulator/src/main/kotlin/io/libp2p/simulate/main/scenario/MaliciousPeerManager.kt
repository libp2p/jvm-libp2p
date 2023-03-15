package io.libp2p.simulate.main.scenario

import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.gossip.GossipSimConfig
import io.libp2p.simulate.gossip.GossipSimPeerConfig
import io.libp2p.simulate.gossip.MessageValidation
import kotlin.time.Duration

class MaliciousPeerManager(
    val maliciousPeerSelector: (SimPeerId) -> Boolean,
    val sourceConfig: GossipSimConfig
) {

    var propagateMessages = true

    val maliciousPeerIds: Set<SimPeerId> =
        (0 until sourceConfig.totalPeers)
            .filter{ maliciousPeerSelector(it) }
            .toSet()

    val maliciousConfig by lazy {
        val modifiedPeerConfigs = sourceConfig.peerConfigs
            .mapIndexed { simPeerId, cfg ->
                if (simPeerId in maliciousPeerIds) {
                    modifyMaliciousPeerConfig(cfg)
                } else {
                    cfg
                }
            }
        sourceConfig.copy(peerConfigs = modifiedPeerConfigs)
    }

    private fun generateValidation() =
        if (propagateMessages) {
            MessageValidation(Duration.ZERO, ValidationResult.Valid)
        } else {
            MessageValidation(Duration.ZERO, ValidationResult.Ignore)
        }

    private fun modifyMaliciousPeerConfig(cfg: GossipSimPeerConfig): GossipSimPeerConfig =
        cfg.copy(
            messageValidationGenerator = { generateValidation() }
        )

}