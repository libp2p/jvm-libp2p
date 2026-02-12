package io.libp2p.pubsub.gossip.extensions

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipTestsBase
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

        val rpcMessageWithControlExtensionAndTestExtensionMessages = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .setExtensions(Rpc.ControlExtensions.newBuilder().setTestExtension(true))
                    .build()
            )
            .setTestExtension(Rpc.TestExtension.newBuilder().build())
            .build()
        test.mockRouter.sendToSingle(rpcMessageWithControlExtensionAndTestExtensionMessages)

        assertNoResponseFromTestExtension(test)
    }

    @Test
    fun `extension messages sent to peer prior to sending extension control messages are ignored`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        val rpcMessageWithTestExtension =
            Rpc.RPC.newBuilder().setTestExtension(testExtensionMessage).build()
        test.mockRouter.sendToSingle(rpcMessageWithTestExtension)

        assertNoResponseFromTestExtension(test)
    }

    @Test
    fun `extension message flow with extension control message before actual extension message`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        val rpcMessageWithControl = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().setExtensions(controlExtensionMessage())
        ).build()
        test.mockRouter.sendToSingle(rpcMessageWithControl)

        val rpcMessageWithTestExtension =
            Rpc.RPC.newBuilder().setTestExtension(testExtensionMessage).build()
        test.mockRouter.sendToSingle(rpcMessageWithTestExtension)

        test.mockRouter.waitForMessage { it.hasTestExtension() }
    }

    @Test
    fun `extension message flow with extension control and extension message in the same rpc message`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3
        )

        val rpcMessageWithControlExtensionAndTestExtensionMessages = Rpc.RPC.newBuilder()
            .setControl(
                Rpc.ControlMessage.newBuilder()
                    .setExtensions(Rpc.ControlExtensions.newBuilder().setTestExtension(true))
                    .build()
            )
            .setTestExtension(Rpc.TestExtension.newBuilder().build())
            .build()
        test.mockRouter.sendToSingle(rpcMessageWithControlExtensionAndTestExtensionMessages)

        test.mockRouter.waitForMessage { it.hasTestExtension() }
    }

    companion object {
        val testExtensionControlEnabledMessage: Rpc.RPC = Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder()
                .setExtensions(Rpc.ControlExtensions.newBuilder().setTestExtension(true).build())
                .build()
        ).build()

        fun controlExtensionMessage(testExtensionEnabled: Boolean = false): Rpc.ControlExtensions {
            return Rpc.ControlExtensions.newBuilder().setTestExtension(testExtensionEnabled).build()
        }

        val testExtensionMessage: Rpc.TestExtension = Rpc.TestExtension.newBuilder().build()

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
