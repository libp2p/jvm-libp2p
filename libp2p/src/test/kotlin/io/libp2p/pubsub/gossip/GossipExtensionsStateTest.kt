package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

class GossipExtensionsStateTest {

    private lateinit var extensionsState: GossipExtensionsState
    private lateinit var peer1: PeerId
    private lateinit var peer2: PeerId
    private lateinit var peer3: PeerId

    @BeforeEach
    fun setup() {
        extensionsState = GossipExtensionsState()
        peer1 = PeerId.random()
        peer2 = PeerId.random()
        peer3 = PeerId.random()
    }

    @Test
    fun `onExtensionControlMessage() stores peer extensions support`() {
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .setTestExtension(false)
            .build()

        extensionsState.onExtensionControlMessage(extension, peer1)

        val stored = extensionsState.peerSupportedExtensions(peer1)
        assertThat(stored).isNotNull
        assertThat(stored!!.partialMessages).isTrue()
        assertThat(stored.testExtension).isFalse()
    }

    @Test
    fun `hasReceivedExtensionControlFrom() returns true after receiving extensions`() {
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()

        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()

        extensionsState.onExtensionControlMessage(extension, peer1)

        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isTrue()
    }

    @Test
    fun `hasReceivedExtensionControlFrom() returns false for unknown peer`() {
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
    }

    @Test
    fun `peerSupportedExtensions() returns null for unknown peer`() {
        val extensions = extensionsState.peerSupportedExtensions(peer1)
        assertThat(extensions).isNull()
    }

    /*
        In practice, we should not receive more than one control message from the same peer on
        the same connection, but if this ever happens, it makes sense to override the in-memory
        config given it most likely has the most up-to-date data for that particular peer
     */
    @Test
    fun `onExtensionControlMessage() overwrites previous extensions from same peer`() {
        val extension1 = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .setTestExtension(false)
            .build()

        val extension2 = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(false)
            .setTestExtension(true)
            .build()

        extensionsState.onExtensionControlMessage(extension1, peer1)
        extensionsState.onExtensionControlMessage(extension2, peer1)

        val stored = extensionsState.peerSupportedExtensions(peer1)
        assertThat(stored).isNotNull
        assertThat(stored!!.partialMessages).isFalse()
        assertThat(stored.testExtension).isTrue()
    }

    @Test
    fun `hasSentExtensionControlTo() returns false for unknown peer`() {
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()
    }

    @Test
    fun `registerControlExtensionMessageSentToPeers() registers peer`() {
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()

        extensionsState.registerControlExtensionMessageSentToPeers(peer1)

        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
    }

    @Test
    fun `hasSentExtensionControlTo() returns true after registration`() {
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)

        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
    }

    @Test
    fun `registerControlExtensionMessageSentToPeers() can register multiple peers`() {
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)
        extensionsState.registerControlExtensionMessageSentToPeers(peer2)

        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
        assertThat(extensionsState.hasSentExtensionControlTo(peer2)).isTrue()
        assertThat(extensionsState.hasSentExtensionControlTo(peer3)).isFalse()
    }

    @Test
    fun `sent and received extension tracking are independent`() {
        // Register that we sent to peer1
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)

        // Receive from peer2
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()
        extensionsState.onExtensionControlMessage(extension, peer2)

        // Verify sent tracking
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
        assertThat(extensionsState.hasSentExtensionControlTo(peer2)).isFalse()

        // Verify received tracking
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer2)).isTrue()
    }

    @Test
    fun `peer can be in both sent and received tracking`() {
        // Register that we sent to peer1
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)

        // Receive from peer1
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()
        extensionsState.onExtensionControlMessage(extension, peer1)

        // Both should be tracked
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isTrue()
    }

    @Test
    fun `tracks multiple peers with different extensions`() {
        val extension1 = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .setTestExtension(false)
            .build()

        val extension2 = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(false)
            .setTestExtension(true)
            .build()

        val extension3 = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .setTestExtension(true)
            .build()

        extensionsState.onExtensionControlMessage(extension1, peer1)
        extensionsState.onExtensionControlMessage(extension2, peer2)
        extensionsState.onExtensionControlMessage(extension3, peer3)

        // Verify each peer has correct extensions
        val stored1 = extensionsState.peerSupportedExtensions(peer1)
        assertThat(stored1!!.partialMessages).isTrue()
        assertThat(stored1.testExtension).isFalse()

        val stored2 = extensionsState.peerSupportedExtensions(peer2)
        assertThat(stored2!!.partialMessages).isFalse()
        assertThat(stored2.testExtension).isTrue()

        val stored3 = extensionsState.peerSupportedExtensions(peer3)
        assertThat(stored3!!.partialMessages).isTrue()
        assertThat(stored3.testExtension).isTrue()

        // Verify all peers are tracked
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isTrue()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer2)).isTrue()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer3)).isTrue()
    }

    @Test
    fun `tracks many peers simultaneously`() {
        val peers = (1..10).map { PeerId.random() }
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()

        peers.forEach { peer ->
            extensionsState.onExtensionControlMessage(extension, peer)
        }

        // Verify all peers are tracked
        peers.forEach { peer ->
            assertThat(extensionsState.hasReceivedExtensionControlFrom(peer)).isTrue()
            assertThat(extensionsState.peerSupportedExtensions(peer)).isNotNull
        }
    }

    @Test
    fun `onPeerDisconnected() removes peer from received extensions map`() {
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()

        extensionsState.onExtensionControlMessage(extension, peer1)
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isTrue()

        extensionsState.onPeerDisconnected(peer1)

        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
        assertThat(extensionsState.peerSupportedExtensions(peer1)).isNull()
    }

    @Test
    fun `onPeerDisconnected() handles unknown peer gracefully`() {
        // Should not throw exception for unknown peer
        extensionsState.onPeerDisconnected(peer1)

        // State should remain empty
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
        assertThat(extensionsState.peerSupportedExtensions(peer1)).isNull()
    }

    @Test
    fun `onPeerDisconnected() only removes specified peer`() {
        val extension1 = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()

        val extension2 = Rpc.ControlExtensions.newBuilder()
            .setTestExtension(true)
            .build()

        extensionsState.onExtensionControlMessage(extension1, peer1)
        extensionsState.onExtensionControlMessage(extension2, peer2)

        // Disconnect peer1
        extensionsState.onPeerDisconnected(peer1)

        // peer1 should be removed
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
        assertThat(extensionsState.peerSupportedExtensions(peer1)).isNull()

        // peer2 should remain
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer2)).isTrue()
        assertThat(extensionsState.peerSupportedExtensions(peer2)).isNotNull
        assertThat(extensionsState.peerSupportedExtensions(peer2)!!.testExtension).isTrue()
    }

    @Test
    fun `multiple disconnects and reconnects work correctly`() {
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()

        // Connect
        extensionsState.onExtensionControlMessage(extension, peer1)
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isTrue()

        // Disconnect
        extensionsState.onPeerDisconnected(peer1)
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()

        // Reconnect with different extensions
        val newExtension = Rpc.ControlExtensions.newBuilder()
            .setTestExtension(true)
            .build()
        extensionsState.onExtensionControlMessage(newExtension, peer1)

        val stored = extensionsState.peerSupportedExtensions(peer1)
        assertThat(stored).isNotNull
        assertThat(stored!!.hasPartialMessages()).isFalse()
        assertThat(stored.testExtension).isTrue()
    }

    @Test
    fun `onPeerDisconnected() removes peer from sent extensions list`() {
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()

        extensionsState.onPeerDisconnected(peer1)

        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()
    }

    @Test
    fun `onPeerDisconnected() removes peer from both sent and received tracking`() {
        // Register sent
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)

        // Register received
        val extension = Rpc.ControlExtensions.newBuilder()
            .setPartialMessages(true)
            .build()
        extensionsState.onExtensionControlMessage(extension, peer1)

        // Verify both tracked
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isTrue()

        // Disconnect
        extensionsState.onPeerDisconnected(peer1)

        // Both should be removed
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
        assertThat(extensionsState.peerSupportedExtensions(peer1)).isNull()
    }

    @Test
    fun `onPeerDisconnected() only removes specified peer from sent list`() {
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)
        extensionsState.registerControlExtensionMessageSentToPeers(peer2)

        extensionsState.onPeerDisconnected(peer1)

        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()
        assertThat(extensionsState.hasSentExtensionControlTo(peer2)).isTrue()
    }

    @Test
    fun `reconnecting peer can have sent extension registered again`() {
        // First connection - register sent
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()

        // Disconnect
        extensionsState.onPeerDisconnected(peer1)
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()

        // Reconnect - register sent again
        extensionsState.registerControlExtensionMessageSentToPeers(peer1)
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isTrue()
    }

    @Test
    fun `querying empty state returns expected values`() {
        extensionsState = GossipExtensionsState()
        assertThat(extensionsState.hasReceivedExtensionControlFrom(peer1)).isFalse()
        assertThat(extensionsState.hasSentExtensionControlTo(peer1)).isFalse()
        assertThat(extensionsState.peerSupportedExtensions(peer1)).isNull()
    }
}
