package io.libp2p.pubsub

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import io.netty.buffer.ByteBuf
import pubsub.pb.Rpc
import java.io.IOException

/**
 * Walks an inbound pubsub RPC [ByteBuf] without materialising any `pubsub.pb.Rpc`
 * message and rejects it if its repeated-field counts violate [PubsubRpcLimits],
 * or if its control-plane wire size violates [PubsubRpcLimits.maxControlMessageSize].
 *
 * Field numbers are taken from the protobuf-generated `*_FIELD_NUMBER` constants,
 * so renames in `libp2p/src/main/proto/rpc.proto` break compilation.
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
    private const val RPC_PARTIAL = Rpc.RPC.PARTIAL_FIELD_NUMBER

    // pubsub.Message field numbers
    private const val MESSAGE_TOPIC_IDS = Rpc.Message.TOPICIDS_FIELD_NUMBER
    private const val MESSAGE_DATA = Rpc.Message.DATA_FIELD_NUMBER

    // pubsub.ControlMessage field numbers
    private const val CONTROL_IHAVE = Rpc.ControlMessage.IHAVE_FIELD_NUMBER
    private const val CONTROL_IWANT = Rpc.ControlMessage.IWANT_FIELD_NUMBER
    private const val CONTROL_GRAFT = Rpc.ControlMessage.GRAFT_FIELD_NUMBER
    private const val CONTROL_PRUNE = Rpc.ControlMessage.PRUNE_FIELD_NUMBER
    private const val CONTROL_IDONTWANT = Rpc.ControlMessage.IDONTWANT_FIELD_NUMBER
    private const val CONTROL_EXTENSIONS = Rpc.ControlMessage.EXTENSIONS_FIELD_NUMBER

    // pubsub.ControlPrune field numbers
    private const val PRUNE_PEERS = Rpc.ControlPrune.PEERS_FIELD_NUMBER

    // pubsub.PartialMessagesExtension field numbers
    private const val PARTIAL_MESSAGE = Rpc.PartialMessagesExtension.PARTIALMESSAGE_FIELD_NUMBER
    private const val PARTS_METADATA = Rpc.PartialMessagesExtension.PARTSMETADATA_FIELD_NUMBER

    /**
     * Running total of fields seen across every nesting level of one RPC, checked against
     * [PubsubRpcLimits.maxTotalFields]. Mutable because the walk is depth-first across several
     * functions and the limit is global to the frame, not per-level.
     */
    private class FieldBudget(private val max: Int?) {
        private var count = 0

        /** Charges one field; returns a rejection once the budget is spent. */
        fun charge(): Result.Rejected? {
            if (max == null) return null
            count++
            return if (count > max) Result.Rejected("total fields > $max") else null
        }
    }

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
        var controlBytes = 0
        val budget = limits.maxControlMessageSize
        val fields = FieldBudget(limits.maxTotalFields)

        while (!input.isAtEnd) {
            fields.charge()?.let { return it }
            val fieldStart = input.totalBytesRead
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            // Bytes consumed by this field that are NOT charged to the control budget.
            var exemptBytes = 0

            when {
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
                    val scan = scanPublish(input, limits.maxTopicsPerPublishedMessage, fields)
                    scan.rejection?.let { return it }
                    exemptBytes = scan.dataBytes
                    input.popLimit(oldLimit)
                }
                fieldNumber == RPC_PARTIAL &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    val scan = scanPartial(input, fields)
                    scan.rejection?.let { return it }
                    exemptBytes = scan.dataBytes
                    input.popLimit(oldLimit)
                }
                fieldNumber == RPC_SUBSCRIPTIONS &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    // SubOpts holds only scalars, so counting its fields is the whole walk.
                    scanFlat(input, fields)?.let { return it }
                    input.popLimit(oldLimit)
                }
                fieldNumber == RPC_CONTROL &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    scanControl(input, fields)?.let { return it }
                    input.popLimit(oldLimit)
                }
                else -> skipCounting(input, tag, fields)?.let { return it }
            }

            if (budget != null) {
                controlBytes += (input.totalBytesRead - fieldStart) - exemptBytes
                if (controlBytes > budget) {
                    return Result.Rejected("control bytes > $budget")
                }
            }
        }
        return Result.Accepted
    }

    private class PublishScan(val rejection: Result.Rejected?, val dataBytes: Int)

    /**
     * Walks one `publish` entry, enforcing [maxTopics] when configured and accumulating the
     * length of its `data` payload so the caller can exempt it from the control budget.
     */
    private fun scanPublish(
        input: CodedInputStream,
        maxTopics: Int?,
        fields: FieldBudget
    ): PublishScan {
        var topicCount = 0
        var dataBytes = 0
        while (!input.isAtEnd) {
            fields.charge()?.let { return PublishScan(it, dataBytes) }
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            when {
                fieldNumber == MESSAGE_TOPIC_IDS &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    topicCount++
                    if (maxTopics != null && topicCount > maxTopics) {
                        return PublishScan(
                            Result.Rejected("topicIDs per publish > $maxTopics"),
                            dataBytes
                        )
                    }
                    input.skipField(tag)
                }
                fieldNumber == MESSAGE_DATA &&
                    wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                    val length = input.readRawVarint32()
                    dataBytes += length
                    input.skipRawBytes(length)
                }
                else -> skipCounting(input, tag, fields)?.let {
                    return PublishScan(it, dataBytes)
                }
            }
        }
        return PublishScan(null, dataBytes)
    }

    /**
     * Sums the payload lengths of the opaque byte fields of a partial-messages extension so the
     * caller can exempt them from the control budget. Everything else inside the extension -
     * including unknown fields - stays charged, because protobuf-java retains unknown fields in an
     * UnknownFieldSet and they are not free.
     */
    private fun scanPartial(input: CodedInputStream, fields: FieldBudget): PublishScan {
        var payloadBytes = 0
        while (!input.isAtEnd) {
            fields.charge()?.let { return PublishScan(it, payloadBytes) }
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            if ((fieldNumber == PARTIAL_MESSAGE || fieldNumber == PARTS_METADATA) &&
                wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED
            ) {
                val length = input.readRawVarint32()
                payloadBytes += length
                input.skipRawBytes(length)
            } else {
                skipCounting(input, tag, fields)?.let { return PublishScan(it, payloadBytes) }
            }
        }
        return PublishScan(null, payloadBytes)
    }

    /**
     * Walks a `ControlMessage`, descending into each control entry so its fields are charged to
     * [fields]. Without this descent the budget would be blind to the cheapest envelope flood
     * available - `skipField` on `control` jumps the whole sub-message in one step, so nothing
     * inside it is ever counted.
     *
     * Entry types are dispatched by field number rather than walked generically because field
     * numbers collide across them: `ControlPrune.peers` is a sub-message at field 2, while
     * `ControlIHave.messageIDs` is opaque bytes at the same number.
     */
    private fun scanControl(input: CodedInputStream, fields: FieldBudget): Result.Rejected? {
        while (!input.isAtEnd) {
            fields.charge()?.let { return it }
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            if (wireType != WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                skipCounting(input, tag, fields)?.let { return it }
                continue
            }
            when (fieldNumber) {
                CONTROL_IHAVE, CONTROL_IWANT, CONTROL_GRAFT, CONTROL_IDONTWANT,
                CONTROL_EXTENSIONS -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    scanFlat(input, fields)?.let { return it }
                    input.popLimit(oldLimit)
                }
                CONTROL_PRUNE -> {
                    val length = input.readRawVarint32()
                    val oldLimit = input.pushLimit(length)
                    scanPrune(input, fields)?.let { return it }
                    input.popLimit(oldLimit)
                }
                else -> skipCounting(input, tag, fields)?.let { return it }
            }
        }
        return null
    }

    /** Walks a `ControlPrune`, descending into its `peers` entries. */
    private fun scanPrune(input: CodedInputStream, fields: FieldBudget): Result.Rejected? {
        while (!input.isAtEnd) {
            fields.charge()?.let { return it }
            val tag = input.readTag()
            val fieldNumber = WireFormat.getTagFieldNumber(tag)
            val wireType = WireFormat.getTagWireType(tag)
            if (fieldNumber == PRUNE_PEERS && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                val length = input.readRawVarint32()
                val oldLimit = input.pushLimit(length)
                scanFlat(input, fields)?.let { return it }
                input.popLimit(oldLimit)
            } else {
                skipCounting(input, tag, fields)?.let { return it }
            }
        }
        return null
    }

    /**
     * Charges every field of a message that holds no sub-messages, skipping their bodies. Used for
     * the leaf shapes: `SubOpts`, `PeerInfo`, and the control entries whose repeated fields are
     * opaque bytes.
     */
    private fun scanFlat(input: CodedInputStream, fields: FieldBudget): Result.Rejected? {
        while (!input.isAtEnd) {
            fields.charge()?.let { return it }
            skipCounting(input, input.readTag(), fields)?.let { return it }
        }
        return null
    }

    /**
     * Skips a field the walker does not descend into, charging the interior of a group to [fields].
     *
     * A group is the one unknown shape protobuf-java expands field by field: it recurses into an
     * unknown group and retains every field inside it in a nested `UnknownFieldSet`, so a group is
     * an allocation vector that plain `skipField` would hide. An unknown *length-delimited* field
     * needs no such treatment - protobuf-java keeps it as a single opaque `ByteString` and never
     * parses its interior, so however many fields it appears to contain, it costs one allocation.
     *
     * Groups are self-delimiting on the wire, so walking to the matching end tag is unambiguous -
     * unlike a length-delimited body, which cannot be told apart from opaque bytes.
     */
    private fun skipCounting(
        input: CodedInputStream,
        tag: Int,
        fields: FieldBudget
    ): Result.Rejected? {
        if (WireFormat.getTagWireType(tag) != WireFormat.WIRETYPE_START_GROUP) {
            input.skipField(tag)
            return null
        }
        // Depth-counted rather than recursive: nesting is attacker-controlled, so the walk must
        // not consume JVM stack proportional to it.
        var depth = 1
        while (depth > 0) {
            fields.charge()?.let { return it }
            val inner = input.readTag()
            if (inner == 0) throw IOException("truncated group")
            when (WireFormat.getTagWireType(inner)) {
                WireFormat.WIRETYPE_START_GROUP -> depth++
                WireFormat.WIRETYPE_END_GROUP -> depth--
                else -> input.skipField(inner)
            }
        }
        return null
    }
}
