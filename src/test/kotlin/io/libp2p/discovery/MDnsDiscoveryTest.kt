package io.libp2p.discovery

import io.libp2p.core.PeerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MDnsDiscoveryTest {
    private val peerId = PeerId.random()

    @Test
    fun startDiscoveryAndListenForSelf() {
        val discoverer = MDnsDiscovery(peerId)

        discoverer.onPeerFound({
            discoverer.stop()
            assertEquals(peerId, it.peerId)
            assertEquals(1, it.addresses.size)
        })

        discoverer.start()
    }
}