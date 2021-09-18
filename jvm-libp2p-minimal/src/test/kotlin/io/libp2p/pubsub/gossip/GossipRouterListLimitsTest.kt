package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.tools.protobuf.RpcBuilder
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class GossipRouterListLimitsTest {

    private val maxPublishedMessages = 10
    private val maxTopicsPerPublishedMessage = 11
    private val maxSubscriptions = 12
    private val maxIHaveLength = 13
    private val maxIWantMessageIds = 14
    private val maxGraftMessages = 15
    private val maxPruneMessages = 16
    private val maxPeersPerPruneMessage = 17

    private val gossipParamsWithLimits = GossipParamsBuilder()
        .maxPublishedMessages(maxPublishedMessages)
        .maxTopicsPerPublishedMessage(maxTopicsPerPublishedMessage)
        .maxSubscriptions(maxSubscriptions)
        .maxIHaveLength(maxIHaveLength)
        .maxIWantMessageIds(maxIWantMessageIds)
        .maxGraftMessages(maxGraftMessages)
        .maxPruneMessages(maxPruneMessages)
        .maxPeersPerPruneMessage(maxPeersPerPruneMessage)
        .build()

    private val gossipParamsNoLimits = GossipParamsBuilder()
        .maxIHaveLength(Int.MAX_VALUE)
        .build()

    @Test
    fun validateProtobufLists_validMessage() {
        val router = GossipRouter(gossipParamsWithLimits)
        val msg = fullMsgBuilder().build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_validMessageWithLargeLists_noLimits() {
        val router = GossipRouter(gossipParamsNoLimits)
        val msg = fullMsgBuilder(20).build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_smallValidMessage_noLimits() {
        val router = GossipRouter(gossipParamsNoLimits)
        val msg = fullMsgBuilder().build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_multipleListsAtMaxSize() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addSubscriptions(maxSubscriptions - 1)
        builder.addPublishMessages(maxPublishedMessages - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_tooManySubscriptions() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addSubscriptions(maxSubscriptions)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPublishMessages() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPublishMessages(maxPublishedMessages, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPublishMessageTopics() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPublishMessages(1, maxTopicsPerPublishedMessage + 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIHaves() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIHaves(maxIHaveLength, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIHaveMsgIds() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIHaves(1, maxIHaveLength)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIWants() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIWants(maxIWantMessageIds, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIWantMsgIds() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIWants(1, maxIWantMessageIds)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyGrafts() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addGrafts(maxGraftMessages)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPrunes() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPrunes(maxPruneMessages, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPrunePeers() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPrunes(1, maxPeersPerPruneMessage + 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_maxSubscriptions() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addSubscriptions(maxSubscriptions - 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPublishMessages() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPublishMessages(maxPublishedMessages - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPublishMessageTopics() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPublishMessages(1, maxTopicsPerPublishedMessage)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIHaves() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIHaves(maxIHaveLength - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIHaveMsgIds() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIHaves(1, maxIHaveLength - 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIWants() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIWants(maxIWantMessageIds - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIWantMsgIds() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addIWants(1, maxIWantMessageIds - 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxGrafts() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addGrafts(maxGraftMessages - 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPrunes() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPrunes(maxPruneMessages - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPrunePeers() {
        val router = GossipRouter(gossipParamsWithLimits)

        val builder = fullMsgBuilder()
        builder.addPrunes(1, maxPeersPerPruneMessage - 1)
        val msg = builder.build()

        Assertions.assertThat(router.validateMessageListLimits(msg)).isTrue()
    }

    private fun fullMsgBuilder(): RpcBuilder {
        return fullMsgBuilder(1)
    }

    private fun fullMsgBuilder(listSize: Int): RpcBuilder {
        val builder = RpcBuilder()

        // Add some data to all possible fields
        builder.addSubscriptions(listSize)
        builder.addPublishMessages(listSize, listSize)
        builder.addIHaves(listSize, listSize)
        builder.addIWants(listSize, listSize)
        builder.addGrafts(listSize)
        builder.addPrunes(listSize, listSize)

        return builder
    }
}
