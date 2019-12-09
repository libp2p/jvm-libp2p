package io.libp2p.discovery

import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MDnsDiscoveryTest {
    private val peerId = PeerId.random()

    @Test
    fun startDiscoveryAndListenForSelf() {
        var peerInfo: PeerInfo? = null
        val discoverer = MDnsDiscovery(peerId)

        discoverer.onPeerFound {
            peerInfo = it;
        }

        discoverer.start()
        TimeUnit.SECONDS.sleep(2)
        discoverer.stop()

        assertEquals(peerId, peerInfo?.peerId)
        assertEquals(1, peerInfo?.addresses?.size)
    }
}