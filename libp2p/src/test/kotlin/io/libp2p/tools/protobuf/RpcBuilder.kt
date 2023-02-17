package io.libp2p.tools.protobuf

import io.libp2p.etc.types.toProtobuf
import pubsub.pb.Rpc
import kotlin.random.Random

class RpcBuilder {
    private val builder: Rpc.RPC.Builder = Rpc.RPC.newBuilder()

    fun build(): Rpc.RPC {
        return builder.build()
    }

    fun addSubscriptions(count: Int) {
        for (i in 0 until count) {
            val subscription = Rpc.RPC.SubOpts.newBuilder().setSubscribe(true).setTopicid(topic(i)).build()
            builder.addSubscriptions(subscription)
        }
    }

    fun addPublishMessages(count: Int, topicsPerMessage: Int) {
        for (i in 0 until count) {
            val msgBuilder = Rpc.Message.newBuilder().setData(Random.nextBytes(6).toProtobuf())
            for (j in 0 until topicsPerMessage) {
                msgBuilder.addTopicIDs(topic(j))
            }
            builder.addPublish(msgBuilder.build())
        }
    }

    fun addIHaves(iHaveCount: Int, messageIdCount: Int) {
        for (i in 0 until iHaveCount) {
            val iHaveBuilder = Rpc.ControlIHave.newBuilder()
            for (j in 0 until messageIdCount) {
                iHaveBuilder.addMessageIDs(Random.nextBytes(6).toProtobuf())
            }
            builder.controlBuilder.addIhave(iHaveBuilder)
        }
    }

    fun addIWants(iWantCount: Int, messageIdCount: Int) {
        for (i in 0 until iWantCount) {
            val iWantBuilder = Rpc.ControlIWant.newBuilder()
            for (j in 0 until messageIdCount) {
                iWantBuilder.addMessageIDs(Random.nextBytes(6).toProtobuf())
            }
            builder.controlBuilder.addIwant(iWantBuilder)
        }
    }

    fun addGrafts(graftCount: Int) {
        for (i in 0 until graftCount) {
            val graftBuilder = Rpc.ControlGraft.newBuilder()
                .setTopicID(topic(i))
            builder.controlBuilder.addGraft(graftBuilder)
        }
    }

    fun addPrunes(pruneCount: Int, peerCount: Int) {
        for (i in 0 until pruneCount) {
            val pruneBuilder = Rpc.ControlPrune.newBuilder()
            for (j in 0 until peerCount) {
                val peer = Rpc.PeerInfo.newBuilder()
                    .setPeerID(Random.nextBytes(6).toProtobuf())
                pruneBuilder.addPeers(peer)
            }
            builder.controlBuilder.addPrune(pruneBuilder)
        }
    }

    private fun topic(idx: Int): String {
        return "topic-$idx"
    }
}
