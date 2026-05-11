package io.libp2p.pubsub.gossip.extensions

import com.google.protobuf.ByteString
import io.libp2p.core.PeerId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipExtension
import io.libp2p.pubsub.gossip.GossipTestsBase
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesHandler
import io.libp2p.pubsub.gossip.partialmessages.PartialMessagesPeerFeedback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.CopyOnWriteArrayList

private const val TIMEOUT_MS = 500L

class PartialMessagesInboundRpcTest : GossipTestsBase() {

    private val topicId = "test-topic"
    private val groupIdBytes = "group-1".toByteArray()

    /** Records each [onIncomingRpc] call for assertion in tests. */
    data class IncomingCall(val from: PeerId, val rpc: Rpc.PartialMessagesExtension)

    private val incomingCalls = CopyOnWriteArrayList<IncomingCall>()

    private val capturingHandler: PartialMessagesHandler<Unit> =
        object : PartialMessagesHandler<Unit> {
            override fun onIncomingRpc(
                from: PeerId,
                peerStates: Map<PeerId, Unit>,
                rpc: Rpc.PartialMessagesExtension,
                feedback: PartialMessagesPeerFeedback
            ) {
                incomingCalls += IncomingCall(from, rpc)
            }

            override fun onEmitGossip(
                topic: Topic,
                groupId: ByteArray,
                gossipPeers: Collection<PeerId>,
                peerStates: Map<PeerId, Unit>,
                feedback: PartialMessagesPeerFeedback
            ) {}
        }

    private fun newTest() = TwoRoutersTest(
        protocol = PubsubProtocol.Gossip_V_1_3,
        enabledGossipExtensions = listOf(GossipExtension.PARTIAL_MESSAGES),
        partialMessagesHandler = capturingHandler,
    )

    private fun partialRpcWith(
        topicId: String? = this.topicId,
        groupId: ByteArray? = groupIdBytes
    ): Rpc.RPC {
        val ext = Rpc.PartialMessagesExtension.newBuilder().apply {
            if (topicId != null) setTopicID(topicId)
            if (groupId != null) setGroupID(ByteString.copyFrom(groupId))
        }.build()
        return Rpc.RPC.newBuilder().setPartial(ext).build()
    }

    private fun controlExtensionsWithPartial(): Rpc.RPC =
        Rpc.RPC.newBuilder().setControl(
            Rpc.ControlMessage.newBuilder().setExtensions(
                Rpc.ControlExtensions.newBuilder().setPartialMessages(true)
            )
        ).build()

    // Drains any currently queued messages from the mock router's outbox
    // so later assertions start from a clean slate.
    private fun TwoRoutersTest.flushRouter() =
        gossipRouter.submitOnEventThread {}.join()

    @Test
    fun `valid partial RPC after ControlExtensions dispatches to handler`() {
        val test = newTest()
        test.gossipRouter.subscribe(topicId)
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith())
        test.flushRouter()

        assertThat(incomingCalls).hasSize(1)
        assertThat(incomingCalls[0].rpc.topicID).isEqualTo(topicId)
        assertThat(incomingCalls[0].rpc.groupID.toByteArray()).isEqualTo(groupIdBytes)
    }

    @Test
    fun `partial RPC without prior ControlExtensions is ignored`() {
        val test = newTest()
        test.flushRouter()

        test.mockRouter.sendToSingle(partialRpcWith())
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }

    @Test
    fun `partial RPC with missing topicID is dropped`() {
        val test = newTest()
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith(topicId = null))
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }

    @Test
    fun `partial RPC with empty topicID is dropped`() {
        val test = newTest()
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith(topicId = ""))
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }

    @Test
    fun `partial RPC with missing groupID is dropped`() {
        val test = newTest()
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith(groupId = null))
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }

    @Test
    fun `partial RPC with empty groupID is dropped`() {
        val test = newTest()
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith(groupId = ByteArray(0)))
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }

    @Test
    fun `partial RPC when extension is disabled is ignored`() {
        val test = TwoRoutersTest(
            protocol = PubsubProtocol.Gossip_V_1_3,
            enabledGossipExtensions = listOf(),
        )
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith())
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }

    @Test
    fun `multiple valid partial RPCs for different groups all dispatched`() {
        val test = newTest()
        test.gossipRouter.subscribe(topicId)
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith(groupId = "g1".toByteArray()))
        test.mockRouter.sendToSingle(partialRpcWith(groupId = "g2".toByteArray()))
        test.flushRouter()

        assertThat(incomingCalls).hasSize(2)
    }

    @Test
    fun `partial RPC for unsubscribed topic is dropped`() {
        val test = newTest()
        // Deliberately do NOT subscribe to "unsubscribed-topic"
        test.flushRouter()

        test.mockRouter.sendToSingle(controlExtensionsWithPartial())
        test.mockRouter.sendToSingle(partialRpcWith(topicId = "unsubscribed-topic"))
        test.flushRouter()

        assertThat(incomingCalls).isEmpty()
    }
}
