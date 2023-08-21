package io.libp2p.pubsub.gossip

import io.libp2p.core.PeerId
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.gossip.builders.GossipParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pubsub.pb.Rpc
import java.util.stream.Stream

class GossipRpcPartsQueueTest {

    class TestGossipQueue(params: GossipParams) : DefaultGossipRpcPartsQueue(params) {

        fun shuffleParts() {
            parts.shuffle()
        }

        fun mergedSingle(): Rpc.RPC {
            val builder = Rpc.RPC.newBuilder()
            parts.forEach {
                it.appendToBuilder(builder)
            }
            return builder.build()
        }
    }

    data class PartCounts(
        val published: Int,
        val subscriptions: Int,
        val iHaves: Int,
        val iWants: Int,
        val grafts: Int,
        val prunes: Int
    ) {

        fun generateQueue(params: GossipParams): TestGossipQueue {
            val queue = TestGossipQueue(params)

            (1..subscriptions).forEach {
                queue.addSubscribe("topic-$it")
            }
            (1..published).forEach {
                queue.addPublish(createRpcMessage("topic-$it", "data"))
            }
            (1..iHaves).forEach {
                queue.addIHave(byteArrayOf(it.toByte()).toWBytes())
            }
            (1..iWants).forEach {
                queue.addIWant(byteArrayOf(it.toByte()).toWBytes())
            }
            (1..grafts).forEach {
                queue.addGraft("topic-$it")
            }
            (1..prunes).forEach {
                queue.addPrune("topic-$it", it.toLong(), listOf(PeerId.random()))
            }
            return queue
        }
    }

    companion object {
        private val maxPublishedMessages = 10
        private val maxSubscriptions = 12
        private val maxIHaveLength = 13
        private val maxIWantMessageIds = 14
        private val maxGraftMessages = 15
        private val maxPruneMessages = 16

        private val gossipParamsWithLimits = GossipParamsBuilder()
            .maxPublishedMessages(maxPublishedMessages)
            .maxSubscriptions(maxSubscriptions)
            .maxIHaveLength(maxIHaveLength)
            .maxIWantMessageIds(maxIWantMessageIds)
            .maxGraftMessages(maxGraftMessages)
            .maxPruneMessages(maxPruneMessages)
            .build()

        private val gossipParamsNoLimits = GossipParamsBuilder()
            .maxIHaveLength(Int.MAX_VALUE)
            .build()

        fun createRpcMessage(topic: String, data: String): Rpc.Message =
            Rpc.Message.newBuilder()
                .addTopicIDs(topic)
                .setData(data.toByteArray().toProtobuf())
                .build()

        fun Rpc.RPC.disperse(): List<Rpc.RPC> {
            val coreMessages = this.subscriptionsList.map {
                Rpc.RPC.newBuilder().addSubscriptions(it)
            } + this.publishList.map {
                Rpc.RPC.newBuilder().addPublish(it)
            }

            val controlMessages = if (this.hasControl()) {
                this.control.run {
                    listOf(
                        ihaveList
                            .flatMap { it.messageIDsList }
                            .map {
                                Rpc.RPC.newBuilder().apply {
                                    controlBuilder.addIhaveBuilder().addMessageIDs(it)
                                }
                            },
                        iwantList
                            .flatMap { it.messageIDsList }
                            .map {
                                Rpc.RPC.newBuilder().apply {
                                    controlBuilder.addIwantBuilder().addMessageIDs(it)
                                }
                            },
                        graftList
                            .map {
                                Rpc.RPC.newBuilder().apply {
                                    controlBuilder.addGraft(it)
                                }
                            },
                        pruneList
                            .map {
                                Rpc.RPC.newBuilder().apply {
                                    controlBuilder.addPrune(it)
                                }
                            }
                    ).flatten()
                }
            } else {
                emptyList()
            }

            return (coreMessages + controlMessages).map { it.build() }
        }

        fun List<Rpc.RPC>.merge(): Rpc.RPC =
            this.fold(Rpc.RPC.newBuilder()) { builder, part ->
                builder.mergeFrom(part)
            }.build()

        val partsCases = listOf(
            PartCounts(0, 0, 0, 0, 0, 0),
            PartCounts(1, 0, 0, 0, 0, 0),
            PartCounts(0, 1, 0, 0, 0, 0),
            PartCounts(0, 0, 0, 0, 1, 0),
            PartCounts(0, 0, 0, 0, 0, 1),
            PartCounts(1, 1, 1, 1, 1, 1),
            PartCounts(10, 12, 13, 14, 15, 16),
            PartCounts(11, 13, 14, 15, 16, 17),
            PartCounts(20, 0, 0, 0, 0, 0),
            PartCounts(21, 0, 0, 0, 0, 0),
            PartCounts(21, 0, 0, 0, 0, 33),
            PartCounts(0, 0, 1, 0, 0, 0),
            PartCounts(0, 0, 13, 0, 0, 0),
            PartCounts(0, 0, 14, 0, 0, 0),
            PartCounts(0, 0, 26, 0, 0, 0),
            PartCounts(0, 0, 27, 0, 0, 0),
            PartCounts(0, 0, 0, 14, 0, 0),
            PartCounts(0, 0, 0, 15, 0, 0),
            PartCounts(0, 0, 0, 28, 0, 0),
            PartCounts(0, 0, 0, 29, 0, 0),
        )

        val testCases = partsCases
            .flatMap { params ->
                listOf(
                    gossipParamsWithLimits,
                    gossipParamsWithLimits
                )
                    .map { params to it }
            }
            .flatMap { (partsCase, gossipParams) ->
                listOf(
                    partsCase.generateQueue(gossipParams),
                    partsCase.generateQueue(gossipParams).also { it.shuffleParts() },
                ).map {
                    Arguments.of(gossipParams, it)
                }
            }

        @JvmStatic
        fun mergeParams(): Stream<Arguments> = testCases.stream()
    }

    @ParameterizedTest(name = "[${ParameterizedTest.INDEX_PLACEHOLDER}] {0}")
    @MethodSource("mergeParams")
    fun `mergeMessageParts() test various combinations`(
        gossipParams: GossipParams,
        queue: TestGossipQueue
    ) {
        val router = GossipRouterBuilder(params = gossipParams).build()

        val monolithMsg = queue.mergedSingle()
        val merged = queue.takeMerged()

        assertThat(merged).allMatch { router.validateMessageListLimits(it) }
        assertThat(merged.merge().disperse().toSet()).isEqualTo(monolithMsg.disperse().toSet())
    }

    @Test
    fun `mergeMessageParts() none parts`() {
        val partsQueue = DefaultGossipRpcPartsQueue(gossipParamsNoLimits)
        assertThat(partsQueue.takeMerged()).isEmpty()
    }

    @Test
    fun `mergeMessageParts() have no control part`() {
        val partsQueue = DefaultGossipRpcPartsQueue(gossipParamsNoLimits)
        partsQueue.addSubscribe("topic")
        partsQueue.addPublish(createRpcMessage("topic", "data-1"))
        partsQueue.addPublish(createRpcMessage("topic", "data-2"))

        assertThat(partsQueue.takeMerged()[0].hasControl()).isFalse()
    }

    @Test
    fun `mergeMessageParts() test that split doesn't result in topic publish before subscribe`() {
        val router = GossipRouterBuilder(params = gossipParamsWithLimits).build()
        val partsQueue = TestGossipQueue(gossipParamsWithLimits)
        (0 until maxSubscriptions + 1).forEach {
            partsQueue.addSubscribe("topic-$it")
        }

        partsQueue.addPublish(createRpcMessage("topic-$maxSubscriptions", "data"))

        val single = partsQueue.mergedSingle()
        val msgs = partsQueue.takeMerged()

        msgs.forEach {
            assertThat(router.validateMessageListLimits(it)).isTrue()
        }
        assertThat(msgs).hasSize(2)
        assertThat(msgs[0].publishCount).isZero()
        assertThat(msgs[1].publishCount).isEqualTo(1)
        assertThat(msgs.merge()).isEqualTo(single)
    }

    @Test
    fun `mergeMessageParts() test that even when all parts fit to 2 messages the result should be 3 messages`() {
        val router = GossipRouterBuilder(params = gossipParamsWithLimits).build()
        val partsQueue = TestGossipQueue(gossipParamsWithLimits)
        (0 until maxSubscriptions + 1).forEach {
            partsQueue.addSubscribe("topic-$it")
        }

        (0 until maxPublishedMessages * 2).forEach {
            partsQueue.addPublish(createRpcMessage("topic-$it", "data"))
        }

        val single = partsQueue.mergedSingle()
        val msgs = partsQueue.takeMerged()

        msgs.forEach {
            assertThat(router.validateMessageListLimits(it)).isTrue()
        }
        assertThat(msgs).hasSize(3)
        assertThat(msgs.merge()).isEqualTo(single)
    }
}
