package io.libp2p.discovery

import io.libp2p.core.PeerId
import io.libp2p.core.PeerInfo
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.crypto.keys.generateEcdsaKeyPair
import io.libp2p.tools.NullHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MDnsDiscoveryTest {
    val host = object : NullHost() {
        override val peerId: PeerId = PeerId.fromPubKey(
            generateEcdsaKeyPair().second
        )

        override fun listenAddresses(): List<Multiaddr> {
            return listOf(
                Multiaddr("/ip4/127.0.0.1/tcp/4000"),
                Multiaddr("/ip4/10.2.7.1/tcp/9999"),
                Multiaddr("/ip6/::1/tcp/5555")
            )
        }
    }

    val hostIpv6 = object : NullHost() {
        override val peerId: PeerId = PeerId.fromPubKey(
            generateEcdsaKeyPair().second
        )

        override fun listenAddresses(): List<Multiaddr> {
            return listOf(
                Multiaddr("/ip6/::/tcp/4001")
            )
        }
    }

    val otherHost = object : NullHost() {
        override val peerId: PeerId = PeerId.fromPubKey(
            generateEcdsaKeyPair().second
        )

        override fun listenAddresses(): List<Multiaddr> {
            return listOf(
                Multiaddr("/ip4/10.2.7.12/tcp/5000")
            )
        }
    }

    val testServiceTag = "_ipfs-test._udp.local."

    @Test
    fun `start and stop discovery`() {
        val discoverer = MDnsDiscovery(host, testServiceTag)

        discoverer.start().get(1, TimeUnit.SECONDS)
        TimeUnit.MILLISECONDS.sleep(100)
        discoverer.stop().get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `start and stop discovery ipv6`() {
        val discoverer = MDnsDiscovery(hostIpv6, testServiceTag)

        discoverer.start().get(1, TimeUnit.SECONDS)
        TimeUnit.MILLISECONDS.sleep(100)
        discoverer.stop().get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `start discovery and listen for self`() {
        var peerInfo: PeerInfo? = null
        val discoverer = MDnsDiscovery(host, testServiceTag)

        discoverer.newPeerFoundListeners += {
            peerInfo = it
        }

        discoverer.start().get(1, TimeUnit.SECONDS)
        for (i in 0..50) {
            if (peerInfo != null) {
                break
            }
            TimeUnit.MILLISECONDS.sleep(100)
        }
        discoverer.stop().get(1, TimeUnit.SECONDS)

        assertEquals(host.peerId, peerInfo?.peerId)
        assertEquals(host.listenAddresses().size, peerInfo?.addresses?.size)
    }

    @Test
    fun `start discovery and listen for self ipv6`() {
        var peerInfo: PeerInfo? = null
        val discoverer = MDnsDiscovery(hostIpv6, testServiceTag)

        discoverer.newPeerFoundListeners += {
            peerInfo = it
        }

        discoverer.start().get(1, TimeUnit.SECONDS)
        for (i in 0..50) {
            if (peerInfo != null) {
                break
            }
            TimeUnit.MILLISECONDS.sleep(100)
        }
        discoverer.stop().get(5, TimeUnit.SECONDS)

        assertEquals(hostIpv6.peerId, peerInfo?.peerId)
        assertTrue(hostIpv6.listenAddresses().size <= peerInfo?.addresses?.size!!)
    }

    @Test
    fun `start discovery and listen for other`() {
        var peerInfo: PeerInfo? = null
        val other = MDnsDiscovery(otherHost, testServiceTag)
        other.start().get(1, TimeUnit.SECONDS)

        val discoverer = MDnsDiscovery(host, testServiceTag)
        discoverer.newPeerFoundListeners += {
            if (it.peerId != host.peerId) {
                peerInfo = it
            }
        }

        discoverer.start().get(1, TimeUnit.SECONDS)
        for (i in 0..50) {
            if (peerInfo != null) {
                break
            }
            TimeUnit.MILLISECONDS.sleep(100)
        }
        assertEquals(otherHost.peerId, peerInfo?.peerId)
        assertEquals(otherHost.listenAddresses().size, peerInfo?.addresses?.size)

        discoverer.stop().get(1, TimeUnit.SECONDS)
        other.stop().get(1, TimeUnit.SECONDS)
    }
}
