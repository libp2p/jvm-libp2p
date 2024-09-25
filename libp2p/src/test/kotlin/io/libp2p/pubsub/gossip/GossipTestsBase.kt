package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.*
import io.libp2p.pubsub.DeterministicFuzz.Companion.createGossipFuzzRouterFactory
import io.libp2p.pubsub.DeterministicFuzz.Companion.createMockFuzzRouterFactory
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.netty.handler.logging.LogLevel
import pubsub.pb.Rpc

abstract class GossipTestsBase {

    protected val GossipScore.testPeerScores get() = (this as DefaultGossipScore).peerScores

    protected fun newProtoMessage(topic: Topic, seqNo: Long, data: ByteArray) =
        Rpc.Message.newBuilder()
            .addTopicIDs(topic)
            .setSeqno(seqNo.toBytesBigEndian().toProtobuf())
            .setData(data.toProtobuf())
            .build()

    protected fun newMessage(topic: Topic, seqNo: Long, data: ByteArray) =
        DefaultPubsubMessage(newProtoMessage(topic, seqNo, data))

    protected fun getMessageId(msg: Rpc.Message): MessageId = msg.from.toWBytes() + msg.seqno.toWBytes()

    class ManyRoutersTest(
        val mockRouterCount: Int = 10,
        val params: GossipParams = GossipParams(),
        val scoreParams: GossipScoreParams = GossipScoreParams(),
        val protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1
    ) {
        val fuzz = DeterministicFuzz()
        val gossipRouterBuilderFactory = { GossipRouterBuilder(protocol = protocol, params = params, scoreParams = scoreParams) }
        val router0 = fuzz.createTestRouter(createGossipFuzzRouterFactory(gossipRouterBuilderFactory))
        val routers = (0 until mockRouterCount).map { fuzz.createTestRouter(createMockFuzzRouterFactory()) }
        val connections = mutableListOf<SemiduplexConnection>()
        val gossipRouter = router0.router as GossipRouter
        val mockRouters = routers.map { it.router as MockRouter }

        fun connectAll() = connect(routers.indices)
        fun connect(routerIndexes: IntRange, outbound: Boolean = true): List<SemiduplexConnection> {
            val list =
                routers.slice(routerIndexes).map {
                    if (outbound) {
                        router0.connectSemiDuplex(it, null, LogLevel.ERROR)
                    } else {
                        it.connectSemiDuplex(router0, null, LogLevel.ERROR)
                    }
                }
            connections += list
            return list
        }

        fun getMockRouter(peerId: PeerId) = mockRouters[routers.indexOfFirst { it.peerId == peerId }]
    }

    class TwoRoutersTest(
        val coreParams: GossipParams = GossipParams(),
        val scoreParams: GossipScoreParams = GossipScoreParams(),
        val mockRouterFactory: DeterministicFuzzRouterFactory = createMockFuzzRouterFactory(),
        val protocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1
    ) {
        val fuzz = DeterministicFuzz()
        val gossipRouterBuilderFactory = { GossipRouterBuilder(protocol = protocol, params = coreParams, scoreParams = scoreParams) }
        val router1 = fuzz.createTestRouter(createGossipFuzzRouterFactory(gossipRouterBuilderFactory))
        val router2 = fuzz.createTestRouter(mockRouterFactory)
        val gossipRouter = router1.router as GossipRouter
        val mockRouter = router2.router as MockRouter

        val connection = router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
    }
}
