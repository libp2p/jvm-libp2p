package io.libp2p.pubsub.gossip.extensions

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipTestsBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pubsub.pb.Rpc
import java.util.concurrent.TimeoutException

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

    companion object {
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
                    500L
                )
            }
        }
    }
}
