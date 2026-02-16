package io.libp2p.pubsub.gossip.extensions

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipTestsBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pubsub.pb.Rpc
import java.util.concurrent.TimeoutException

private const val DEFAULT_WAIT_TIMEOUT_IN_MILLIS = 500L

class GossipExtensionsMessageHandlingTest : GossipTestsBase() {

    @Test
    fun `extension messages sent to peer prior to gossip v1_3 are ignored`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_2
        )

        test.mockRouter.sendToSingle(rpcMsgWithCtrlExtensionsAndTestExtension)
        assertNoResponseFromTestExtension(test)
    }

    @Test
    fun `extension messages sent to peer prior to sending extension control messages are ignored`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        test.mockRouter.sendToSingle(rpcMessageWithTestExtension)
        assertNoResponseFromTestExtension(test)
    }

    @Test
    fun `extension message flow with extension control message before actual extension message`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        test.mockRouter.sendToSingle(rpcMessageWithControlExtensions)
        assertThat(test.gossipRouter.gossipExtensionsState.peerSupportedExtensions(test.router2.peerId)).isEqualTo(
            rpcMessageWithControlExtensions.control.extensions
        )

        test.mockRouter.sendToSingle(rpcMessageWithTestExtension)
        test.mockRouter.waitForMessage { it.hasTestExtension() }
    }

    @Test
    fun `extension message flow with extension control and extension message in the same rpc message`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        test.mockRouter.sendToSingle(rpcMsgWithCtrlExtensionsAndTestExtension)
        test.mockRouter.waitForMessage { it.hasTestExtension() }
    }

    @Test
    fun `remove peer extension control map when disconnecting`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        test.mockRouter.sendToSingle(rpcMsgWithCtrlExtensionsAndTestExtension)

        assertThat(test.gossipRouter.gossipExtensionsState.peerSupportedExtensions(test.router2.peerId)).isEqualTo(
            rpcMsgWithCtrlExtensionsAndTestExtension.control.extensions
        )

        test.mockRouter.waitForMessage { it.hasTestExtension() }

        // Successfully registered peer2 extensions support

        assertThat(test.gossipRouter.gossipExtensionsState.peerSupportedExtensions(test.router2.peerId)).isNotNull()

        test.connection.disconnect()

        // After disconnecting removes peer2 from extensions support map
        assertThat(test.gossipRouter.gossipExtensionsState.peerSupportedExtensions(test.router2.peerId)).isNull()
    }

    @ParameterizedTest
    @MethodSource("protocolVersionsWithExtensionSupport")
    fun `control extension message sent to peer on connection with extension support`(protocol: PubsubProtocol) {
        val test = TwoRoutersTest(protocol = protocol)

        val receivedMessage = test.mockRouter.waitForMessage(
            { it.hasControl() && it.control.hasExtensions() },
            DEFAULT_WAIT_TIMEOUT_IN_MILLIS
        )

        assertThat(receivedMessage.control.extensions.partialMessages).isTrue()
        assertThat(receivedMessage.control.extensions.testExtension).isTrue()
    }

    @ParameterizedTest
    @MethodSource("protocolVersionsWithoutExtensionSupport")
    fun `control extension message not sent to peer on connection without extension support`(protocol: PubsubProtocol) {
        val test = TwoRoutersTest(protocol = protocol)

        // Should not receive control extension message on versions without extension support
        assertThrows<TimeoutException> {
            test.mockRouter.waitForMessage(
                { it.hasControl() && it.control.hasExtensions() },
                DEFAULT_WAIT_TIMEOUT_IN_MILLIS
            )
        }
    }

    @Test
    fun `control extension message contains all supported extensions flags`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        val receivedMessage = test.mockRouter.waitForMessage(
            { it.hasControl() && it.control.hasExtensions() },
            2000L
        )

        val extensions = receivedMessage.control.extensions

        // Verify both extension flags are set
        assertThat(extensions.hasPartialMessages()).isTrue()
        assertThat(extensions.partialMessages).isTrue()
        assertThat(extensions.hasTestExtension()).isTrue()
        assertThat(extensions.testExtension).isTrue()
    }

    @Test
    fun `extension state tracks that we sent control extension to peer`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        // Wait for control extension message to be sent
        test.mockRouter.waitForMessage(
            { it.hasControl() && it.control.hasExtensions() },
            DEFAULT_WAIT_TIMEOUT_IN_MILLIS
        )

        // Should be tracked in state
        assertThat(test.gossipRouter.gossipExtensionsState.hasSentExtensionControlTo(test.router2.peerId)).isTrue()
    }

    @Test
    fun `control extension sent state cleared on peer disconnect`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        // Wait for control extension message
        test.mockRouter.waitForMessage(
            { it.hasControl() && it.control.hasExtensions() },
            DEFAULT_WAIT_TIMEOUT_IN_MILLIS
        )

        // Verify it's tracked
        assertThat(test.gossipRouter.gossipExtensionsState.hasSentExtensionControlTo(test.router2.peerId)).isTrue()

        // Disconnect
        test.connection.disconnect()

        // Should be cleared from sent tracking
        assertThat(test.gossipRouter.gossipExtensionsState.hasSentExtensionControlTo(test.router2.peerId)).isFalse()
    }

    companion object {
        @JvmStatic
        fun protocolVersionsWithExtensionSupport() = listOf(
            PubsubProtocol.Gossip_V_1_3
        )

        @JvmStatic
        fun protocolVersionsWithoutExtensionSupport() = listOf(
            PubsubProtocol.Gossip_V_1_1,
            PubsubProtocol.Gossip_V_1_2
        )

        val testExtensionMessage: Rpc.TestExtension = Rpc.TestExtension.newBuilder().build()

        val rpcMessageWithControlExtensions = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().setExtensions(controlExtensionMessage())
        ).build()!!

        val rpcMessageWithTestExtension =
            Rpc.RPC.newBuilder().setTestExtension(testExtensionMessage).build()!!

        // An RPC message with both ControlExtensions and TestExtension message (test extension enabled on control)
        val rpcMsgWithCtrlExtensionsAndTestExtension = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .setExtensions(Rpc.ControlExtensions.newBuilder().setTestExtension(true))
                    .build()
            )
            .setTestExtension(Rpc.TestExtension.newBuilder().build())
            .build()!!

        fun controlExtensionMessage(testExtensionEnabled: Boolean = true): Rpc.ControlExtensions {
            return Rpc.ControlExtensions.newBuilder().setTestExtension(testExtensionEnabled).build()
        }

        fun assertNoResponseFromTestExtension(test: TwoRoutersTest) {
            assertThrows<TimeoutException> {
                test.mockRouter.waitForMessage(
                    { it.hasTestExtension() },
                    DEFAULT_WAIT_TIMEOUT_IN_MILLIS
                )
            }
        }
    }
}
