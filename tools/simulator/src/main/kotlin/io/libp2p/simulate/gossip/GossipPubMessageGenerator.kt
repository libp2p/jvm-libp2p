package io.libp2p.simulate.gossip

import com.google.protobuf.ByteString
import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toLongBigEndian
import io.libp2p.simulate.util.MsgSizeEstimator
import pubsub.pb.Rpc
import java.nio.ByteOrder

class GossipPubMessageGenerator(
    val sizeEstimator: MsgSizeEstimator,
    val messageIdRetriever: (ByteArray) -> Long,
    val msgGenerator: (messageId: Long, size: Int) -> ByteArray
)

fun genericPubSubMsgSizeEstimator(
    publishDataSizeEstimator: (ByteString) -> Int,
    measureTcpOverhead: Boolean = true
): MsgSizeEstimator = { msg: Any ->
    val payloadSize = (msg as Rpc.RPC).run {
        subscriptionsList.sumOf { it.topicid.length + 2 } +
            control.graftList.sumOf { it.topicID.length + 1 } +
            control.pruneList.sumOf { it.topicID.length + 1 } +
            control.ihaveList.flatMap { it.messageIDsList }.sumOf { it.size() + 1 } +
            control.iwantList.flatMap { it.messageIDsList }.sumOf { it.size() + 1 } +
            publishList.sumOf { publishDataSizeEstimator(it.data) + it.topicIDsList.sumOf { it.length } + 224 } +
            6
    }
    (payloadSize + if (measureTcpOverhead) ((payloadSize / 1460) + 1) * 40 else 0).toLong()
}

private fun generateIdBytes(id: Long): ByteArray = id.toBytesBigEndian()
private fun readIdBytes(bytes: ByteArray): Long = bytes.toLongBigEndian()

fun averagePubSubMsgSizeEstimator(avrgMsgLen: Int, measureTcpOverhead: Boolean = true) =
    GossipPubMessageGenerator(
        genericPubSubMsgSizeEstimator({ avrgMsgLen }, measureTcpOverhead),
        { readIdBytes(it) },
        { id, _ -> generateIdBytes(id) }
    )

fun strictPubSubMsgSizeEstimator(measureTcpOverhead: Boolean = true) =
    GossipPubMessageGenerator(
        genericPubSubMsgSizeEstimator({ it.size() }, measureTcpOverhead),
        { bytes -> readIdBytes(bytes.copyOfRange(0, 8)) },
        { id, size -> generateIdBytes(id) + ByteArray(size) }
    )

fun trickyPubSubMsgSizeEstimator(measureTcpOverhead: Boolean = true) =
    GossipPubMessageGenerator(
        genericPubSubMsgSizeEstimator(
            { it.asReadOnlyByteBuffer().order(ByteOrder.BIG_ENDIAN).getInt(8) },
            measureTcpOverhead
        ),
        { bytes -> readIdBytes(bytes.copyOfRange(0, 8)) },
        { id, size -> generateIdBytes(id) + size.toBytesBigEndian() }
    )
