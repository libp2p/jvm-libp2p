package io.libp2p.pubsub

import com.google.protobuf.CodedInputStream
import com.google.protobuf.Descriptors
import com.google.protobuf.WireFormat
import io.netty.buffer.ByteBuf
import pubsub.pb.Rpc
import java.io.IOException

/**
 * Walks an inbound pubsub RPC [ByteBuf] without materialising any `pubsub.pb.Rpc`
 * message and rejects it if its repeated-field counts violate [PubsubRpcLimits].
 *
 * Field numbers are taken from the protobuf-generated `*_FIELD_NUMBER` constants,
 * so renames in `libp2p/src/main/proto/rpc.proto` break compilation. New repeated
 * fields are caught by `RpcMessageCountValidatorProtoCoverageTest`, which
 * recursively walks the descriptors reachable from [Rpc.RPC] and asserts each one
 * appears in [ACKNOWLEDGED_REPEATED_FIELDS].
 *
 * The walker uses [CodedInputStream] to read tags / lengths and to skip bodies,
 * so no `Rpc$Message` / builder is allocated for rejected frames.
 */
object RpcMessageCountValidator {

    sealed interface Result {
        object Accepted : Result
        data class Rejected(val reason: String) : Result
        data class Malformed(val reason: String) : Result
    }

    // pubsub.RPC field numbers
    private const val RPC_SUBSCRIPTIONS = Rpc.RPC.SUBSCRIPTIONS_FIELD_NUMBER
    private const val RPC_PUBLISH = Rpc.RPC.PUBLISH_FIELD_NUMBER
    private const val RPC_CONTROL = Rpc.RPC.CONTROL_FIELD_NUMBER

    // pubsub.Message field numbers
    private const val MESSAGE_TOPIC_IDS = Rpc.Message.TOPICIDS_FIELD_NUMBER

    // pubsub.ControlMessage field numbers
    private const val CTRL_IHAVE = Rpc.ControlMessage.IHAVE_FIELD_NUMBER
    private const val CTRL_IWANT = Rpc.ControlMessage.IWANT_FIELD_NUMBER
    private const val CTRL_GRAFT = Rpc.ControlMessage.GRAFT_FIELD_NUMBER
    private const val CTRL_PRUNE = Rpc.ControlMessage.PRUNE_FIELD_NUMBER
    private const val CTRL_IDONTWANT = Rpc.ControlMessage.IDONTWANT_FIELD_NUMBER

    // pubsub.ControlIHave / ControlIWant / ControlIDontWant repeated bytes field numbers
    private const val IHAVE_MESSAGE_IDS = Rpc.ControlIHave.MESSAGEIDS_FIELD_NUMBER
    private const val IWANT_MESSAGE_IDS = Rpc.ControlIWant.MESSAGEIDS_FIELD_NUMBER
    private const val IDONTWANT_MESSAGE_IDS = Rpc.ControlIDontWant.MESSAGEIDS_FIELD_NUMBER

    // pubsub.ControlPrune.peers
    private const val PRUNE_PEERS = Rpc.ControlPrune.PEERS_FIELD_NUMBER

    /**
     * Single source of truth for every repeated proto field the validator inspects.
     * The proto-coverage test asserts this map equals the set of repeated fields
     * actually present in the proto, recursively from [Rpc.RPC]. Any new repeated
     * field that lands in `rpc.proto` without being added here will fail the test.
     */
    internal val ACKNOWLEDGED_REPEATED_FIELDS: Map<Descriptors.Descriptor, Set<Int>> = mapOf(
        Rpc.RPC.getDescriptor() to setOf(RPC_SUBSCRIPTIONS, RPC_PUBLISH),
        Rpc.Message.getDescriptor() to setOf(MESSAGE_TOPIC_IDS),
        Rpc.ControlMessage.getDescriptor() to setOf(
            CTRL_IHAVE,
            CTRL_IWANT,
            CTRL_GRAFT,
            CTRL_PRUNE,
            CTRL_IDONTWANT
        ),
        Rpc.ControlIHave.getDescriptor() to setOf(IHAVE_MESSAGE_IDS),
        Rpc.ControlIWant.getDescriptor() to setOf(IWANT_MESSAGE_IDS),
        Rpc.ControlIDontWant.getDescriptor() to setOf(IDONTWANT_MESSAGE_IDS),
        Rpc.ControlPrune.getDescriptor() to setOf(PRUNE_PEERS),
    )

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

    private class ControlCounters {
        var ihaveMsgIds = 0
        var iwantMsgIds = 0
        var graftCount = 0
        var pruneCount = 0
        var idontwantCount = 0
        var idontwantMsgIds = 0
    }

    private fun validateRpc(input: CodedInputStream, limits: PubsubRpcLimits): Result {
        var publishCount = 0
        var subscriptionCount = 0
        val ctrl = ControlCounters()

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
                    val res = validateControl(input, limits, ctrl)
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

    private fun validateControl(
        input: CodedInputStream,
        limits: PubsubRpcLimits,
        c: ControlCounters,
    ): Result {
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
                    c.ihaveMsgIds += count
                    limits.maxIHaveMessageIds?.let {
                        if (c.ihaveMsgIds > it) return Result.Rejected("ihave messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_IWANT -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IWANT_MESSAGE_IDS)
                    c.iwantMsgIds += count
                    limits.maxIWantMessageIds?.let {
                        if (c.iwantMsgIds > it) return Result.Rejected("iwant messageIDs > $it")
                    }
                    input.popLimit(oldLimit)
                }
                CTRL_GRAFT -> {
                    c.graftCount++
                    limits.maxGraftMessages?.let {
                        if (c.graftCount > it) return Result.Rejected("graft count > $it")
                    }
                    input.skipField(tag)
                }
                CTRL_PRUNE -> {
                    c.pruneCount++
                    limits.maxPruneMessages?.let {
                        if (c.pruneCount > it) return Result.Rejected("prune count > $it")
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
                    c.idontwantCount++
                    limits.maxIDontWantMessages?.let {
                        if (c.idontwantCount > it) return Result.Rejected("idontwant count > $it")
                    }
                    val length = input.readRawVarint32()
                    if (length == 0 && limits.rejectEmptyIDontWantEntries) {
                        return Result.Rejected("empty idontwant entry")
                    }
                    val oldLimit = input.pushLimit(length)
                    val count = countRepeatedBytes(input, IDONTWANT_MESSAGE_IDS)
                    c.idontwantMsgIds += count
                    limits.maxIDontWantMessageIds?.let {
                        if (c.idontwantMsgIds > it) return Result.Rejected("idontwant messageIDs > $it")
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
