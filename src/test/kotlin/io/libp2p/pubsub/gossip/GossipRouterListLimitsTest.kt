package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
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

    private val routerWithLimits = GossipRouterBuilder(params = gossipParamsWithLimits).build()
    private val routerWithNoLimits = GossipRouterBuilder(params = gossipParamsNoLimits).build()

    @Test
    fun validateProtobufLists_validMessage() {
        val msg = fullMsgBuilder().build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_validMessageWithLargeLists_noLimits() {
        val msg = fullMsgBuilder(20).build()

        Assertions.assertThat(routerWithNoLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_smallValidMessage_noLimits() {
        val msg = fullMsgBuilder().build()

        Assertions.assertThat(routerWithNoLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_multipleListsAtMaxSize() {
        val builder = fullMsgBuilder()
        builder.addSubscriptions(maxSubscriptions - 1)
        builder.addPublishMessages(maxPublishedMessages - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_tooManySubscriptions() {
        val builder = fullMsgBuilder()
        builder.addSubscriptions(maxSubscriptions)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPublishMessages() {
        val builder = fullMsgBuilder()
        builder.addPublishMessages(maxPublishedMessages, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPublishMessageTopics() {
        val builder = fullMsgBuilder()
        builder.addPublishMessages(1, maxTopicsPerPublishedMessage + 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIHaves() {
        val builder = fullMsgBuilder()
        builder.addIHaves(maxIHaveLength, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIHaveMsgIds() {
        val builder = fullMsgBuilder()
        builder.addIHaves(1, maxIHaveLength)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIWants() {
        val builder = fullMsgBuilder()
        builder.addIWants(maxIWantMessageIds, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyIWantMsgIds() {
        val builder = fullMsgBuilder()
        builder.addIWants(1, maxIWantMessageIds)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyGrafts() {
        val builder = fullMsgBuilder()
        builder.addGrafts(maxGraftMessages)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPrunes() {
        val builder = fullMsgBuilder()
        builder.addPrunes(maxPruneMessages, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_tooManyPrunePeers() {
        val builder = fullMsgBuilder()
        builder.addPrunes(1, maxPeersPerPruneMessage + 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isFalse()
    }

    @Test
    fun validateProtobufLists_maxSubscriptions() {
        val builder = fullMsgBuilder()
        builder.addSubscriptions(maxSubscriptions - 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPublishMessages() {
        val builder = fullMsgBuilder()
        builder.addPublishMessages(maxPublishedMessages - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPublishMessageTopics() {
        val builder = fullMsgBuilder()
        builder.addPublishMessages(1, maxTopicsPerPublishedMessage)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIHaves() {
        val builder = fullMsgBuilder()
        builder.addIHaves(maxIHaveLength - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIHaveMsgIds() {
        val builder = fullMsgBuilder()
        builder.addIHaves(1, maxIHaveLength - 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIWants() {
        val builder = fullMsgBuilder()
        builder.addIWants(maxIWantMessageIds - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxIWantMsgIds() {
        val builder = fullMsgBuilder()
        builder.addIWants(1, maxIWantMessageIds - 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxGrafts() {
        val builder = fullMsgBuilder()
        builder.addGrafts(maxGraftMessages - 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPrunes() {
        val builder = fullMsgBuilder()
        builder.addPrunes(maxPruneMessages - 1, 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_maxPrunePeers() {
        val builder = fullMsgBuilder()
        builder.addPrunes(1, maxPeersPerPruneMessage - 1)
        val msg = builder.build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
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
