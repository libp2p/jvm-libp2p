package io.libp2p.pubsub

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import io.netty.buffer.ByteBuf
import java.io.IOException

/**
 * Walks an inbound pubsub RPC [ByteBuf] without materialising any `pubsub.pb.Rpc`
 * message and rejects it if its repeated-field counts violate [PubsubRpcLimits].
 *
 * Field numbers mirror `libp2p/src/main/proto/rpc.proto`. The walker uses
 * [CodedInputStream] to read tags / lengths and to skip bodies, so no
 * `Rpc$Message` / builder is allocated for rejected frames.
 */
object RpcMessageCountValidator {

    sealed interface Result {
        object Accepted : Result
        data class Rejected(val reason: String) : Result
        data class Malformed(val reason: String) : Result
    }

    // pubsub.RPC field numbers
    private const val RPC_SUBSCRIPTIONS = 1
    private const val RPC_PUBLISH = 2
    private const val RPC_CONTROL = 3

    // pubsub.Message field numbers
    private const val MESSAGE_TOPIC_IDS = 4

    // pubsub.ControlMessage field numbers
    private const val CTRL_IHAVE = 1
    private const val CTRL_IWANT = 2
    private const val CTRL_GRAFT = 3
    private const val CTRL_PRUNE = 4
    private const val CTRL_IDONTWANT = 5

    // pubsub.ControlIHave / ControlIWant / ControlIDontWant repeated bytes field numbers
    private const val IHAVE_MESSAGE_IDS = 2
    private const val IWANT_MESSAGE_IDS = 1
    private const val IDONTWANT_MESSAGE_IDS = 1

    // pubsub.ControlPrune.peers
    private const val PRUNE_PEERS = 2

    fun validate(buf: ByteBuf, limits: PubsubRpcLimits): Result {
        val input = CodedInputStream.newInstance(buf.nioBuffer())
        return try {
            validateRpc(input, limits)
        } catch (e: IOException) {
            Result.Malformed("malformed: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            Result.Malformed("malformed: truncated (${e.message})")
        }
    }

    private fun validateRpc(input: CodedInputStream, limits: PubsubRpcLimits): Result {
        var publishCount = 0
        var subscriptionCount = 0

        while (!input.isAtEnd) {
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            when {
                fieldNumber == RPC_SUBSCRIPTIONS &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    subscriptionCount++
                    limits.maxSubscriptions?.let {
                        if (subscriptionCount > it) return Result.Rejected("subscriptions count > $it")
                    }
                    input.skipField(tag)
                }
                fieldNumber == RPC_PUBLISH &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    if (length == 0 && limits.rejectEmptyPublishEntries) {
                        return Result.Rejected("empty publish entry")
                    }
                    publishCount++
                    limits.maxPublishedMessages?.let {
                        if (publishCount > it) return Result.Rejected("publish count > $it")
                    }
                    val oldLimit = input.pushLimit(length)
                    val maxTopics = limits.maxTopicsPerPublishedMessage
                    if (maxTopics != null) {
                        val res = validatePublish(input, maxTopics)
                        if (res is Result.Rejected) return res
                    } else {
                        input.skipMessage()
                    }
                    input.popLimit(oldLimit)
                }
                fieldNumber == RPC_CONTROL &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val res = validateControl(input, limits)
                    if (res is Result.Rejected) return res
                    input.popLimit(oldLimit)
                }
                else -> input.skipField(tag)
            }
        }
        return Result.Accepted
    }

    private fun validatePublish(input: CodedInputStream, maxTopics: Int): Result {
        var topicCount = 0
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (WireFormat.getTagFieldNumber(tag) == MESSAGE_TOPIC_IDS &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                topicCount++
                if (topicCount > maxTopics) return Result.Rejected("topicIDs per publish > $maxTopics")
                input.skipField(tag)
            } else {
                input.skipField(tag)
            }
        }
        return Result.Accepted
    }

    private fun validateControl(input: CodedInputStream, limits: PubsubRpcLimits): Result {
        var ihaveMsgIds = 0
        var iwantMsgIds = 0
        var graftCount = 0
        var pruneCount = 0
        var idontwantCount = 0
        var idontwantMsgIds = 0

        while (!input.isAtEnd) {
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                input.skipField(tag)
                continue
            }
            when (fieldNumber) {
                CTRL_IHAVE -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IHAVE_MESSAGE_IDS)
                    ihaveMsgIds += count
                    limits.maxIHaveMessageIds?.let {
                        if (ihaveMsgIds > it) return Result.Rejected("ihave messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_IWANT -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IWANT_MESSAGE_IDS)
                    iwantMsgIds += count
                    limits.maxIWantMessageIds?.let {
                        if (iwantMsgIds > it) return Result.Rejected("iwant messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_GRAFT -> {
                    graftCount++
                    limits.maxGraftMessages?.let {
                        if (graftCount > it) return Result.Rejected("graft count > $it")
                    }
                    input.skipField(tag)
                }
                CTRL_PRUNE -> {
                    pruneCount++
                    limits.maxPruneMessages?.let {
                        if (pruneCount > it) return Result.Rejected("prune count > $it")
                    }
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val maxPeers = limits.maxPeersPerPruneMessage
                    if (maxPeers != null) {
                        val peerCount = countRepeatedMessages(input, PRUNE_PEERS)
                        if (peerCount > maxPeers) return Result.Rejected("peers per prune > $maxPeers")
                    } else {
                        input.skipMessage()
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_IDONTWANT -> {
                    idontwantCount++
                    limits.maxIDontWantMessages?.let {
                        if (idontwantCount > it) return Result.Rejected("idontwant count > $it")
                    }
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IDONTWANT_MESSAGE_IDS)
                    idontwantMsgIds += count
                    limits.maxIDontWantMessageIds?.let {
                        if (idontwantMsgIds > it) return Result.Rejected("idontwant messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                else -> input.skipField(tag)
            }
        }
        return Result.Accepted
    }

    /**
     * Counts occurrences of a length-delimited repeated field inside a sub-message
     * region. The [CodedInputStream] must already be bounded by a `pushLimit` on the
     * caller side; this method walks until `isAtEnd` and skips every body.
     */
    private fun countRepeatedBytes(input: CodedInputStream, fieldNumber: Int): Int {
        var count = 0
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (WireFormat.getTagFieldNumber(tag) == fieldNumber &&
                WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                count++
            }
            input.skipField(tag)
        }
        return count
    }

    private fun countRepeatedMessages(input: CodedInputStream, fieldNumber: Int): Int =
        countRepeatedBytes(input, fieldNumber)
}
