package io.libp2p.pubsub.gossip

import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.tools.protobuf.RpcBuilder
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class GossipRouterListLimitsTest {

    private val maxPublishedMessages = 10
    private val maxTopicsPerPublishedMessage = 11

    private val gossipParamsWithLimits = GossipParamsBuilder()
        .maxPublishedMessages(maxPublishedMessages)
        .maxTopicsPerPublishedMessage(maxTopicsPerPublishedMessage)
        .build()

    private val gossipParamsNoLimits = GossipParamsBuilder()
        .build()

    private val routerWithLimits = GossipRouterBuilder(params = gossipParamsWithLimits).build()
    private val routerWithNoLimits = GossipRouterBuilder(params = gossipParamsNoLimits).build()

    private val topic: Topic = "topic1"

    @Test
    fun validateProtobufLists_validMessage() {
        val msg = fullMsgBuilder().build()

        Assertions.assertThat(routerWithLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_validMessageWithLargeLists_noLimits() {
        val msg = fullMsgBuilder(16).build()

        Assertions.assertThat(routerWithNoLimits.validateMessageListLimits(msg)).isTrue()
    }

    @Test
    fun validateProtobufLists_smallValidMessage_noLimits() {
        val msg = fullMsgBuilder().build()

        Assertions.assertThat(routerWithNoLimits.validateMessageListLimits(msg)).isTrue()
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

    private fun fullMsgBuilder(): RpcBuilder {
        return fullMsgBuilder(1)
    }

    private fun fullMsgBuilder(listSize: Int): RpcBuilder {
        val builder = RpcBuilder()

        // Add some data to all possible fields
        builder.addSubscriptions(listSize)
        builder.addPublishMessages(listSize, listSize)
        builder.addIHaves(listSize, listSize, topic)
        builder.addIWants(listSize, listSize)
        builder.addGrafts(listSize)
        builder.addPrunes(listSize, listSize)

        return builder
    }
}
