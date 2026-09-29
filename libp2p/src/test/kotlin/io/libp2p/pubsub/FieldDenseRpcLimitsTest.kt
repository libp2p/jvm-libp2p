package io.libp2p.pubsub

import io.libp2p.pubsub.gossip.GossipParams
import io.netty.buffer.Unpooled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the protobuf encodings that are cheap in bytes but expensive in fields, which is the gap
 * [GossipParams.maxTotalFields] exists to close.
 *
 * Every shape here costs two wire bytes per field, so a frame can carry tens of thousands of them
 * and still sit well inside [GossipParams.maxControlMessageSize]. Each test asserts that the frame
 * stays under the byte budget, which is what makes the field budget the only limit that can act on
 * it, and therefore what makes this a test of field accounting rather than a restatement of the
 * byte-budget tests.
 *
 * Each shape is asserted in both directions: rejected under production defaults, and *accepted*
 * once `maxTotalFields` is removed. The second assertion is the load-bearing one. Without it a
 * green test would show only that the frames are well formed, not that the field budget is what
 * bounds them.
 */
class FieldDenseRpcLimitsTest {

    /** Field occurrences per frame, just over the 65536 budget so the walk short-circuits early. */
    private val occurrences = 70_000

    /** Mirrors `GossipRouter.rpcLimits` at stock configuration. */
    private val production = GossipParams().let { params ->
        PubsubRpcLimits(
            maxPublishedMessages = params.maxPublishedMessages,
            maxTopicsPerPublishedMessage = params.maxTopicsPerPublishedMessage,
            rejectEmptyPublishEntries = true,
            maxControlMessageSize = params.maxControlMessageSize,
            maxTotalFields = params.maxTotalFields,
        )
    }

    /** The same limits with the field budget removed. */
    private val withoutFieldBudget = production.copy(maxTotalFields = null)

    private fun validate(frame: ByteArray, limits: PubsubRpcLimits) =
        RpcMessageCountValidator.validate(Unpooled.wrappedBuffer(frame), limits)

    /**
     * Asserts the field budget is both effective and load-bearing: the frame is rejected under
     * production defaults, stays inside the byte budget, and is accepted once the field budget is
     * removed.
     */
    private fun assertBoundedOnlyByFieldBudget(shape: String, frame: ByteArray) {
        assertThat(validate(frame, production))
            .withFailMessage("%s (%d bytes) was not rejected under production defaults", shape, frame.size)
            .isInstanceOf(RpcMessageCountValidator.Result.Rejected::class.java)

        assertThat(frame.size)
            .withFailMessage("%s must stay under the control byte budget to isolate the field budget", shape)
            .isLessThan(production.maxControlMessageSize!!)

        assertThat(validate(frame, withoutFieldBudget))
            .withFailMessage("%s was rejected with maxTotalFields=null, so it does not isolate the field budget", shape)
            .isEqualTo(RpcMessageCountValidator.Result.Accepted)
    }

    private fun frameOf(size: Int, build: (java.io.ByteArrayOutputStream) -> Unit): ByteArray =
        java.io.ByteArrayOutputStream(size).also(build).toByteArray()

    private fun java.io.ByteArrayOutputStream.varint(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    @Test
    fun `repeated unknown fields are counted per occurrence`() {
        // Unknown field 14, varint wire type, repeated. protobuf-java retains each occurrence in an
        // UnknownFieldSet, so each one is a live object rather than a skipped byte range.
        val frame = frameOf(occurrences * 2) { out ->
            repeat(occurrences) {
                out.write(0x70)
                out.write(0x01)
            }
        }
        assertBoundedOnlyByFieldBudget("unknown fields", frame)
    }

    @Test
    fun `a known field carrying the wrong wire type is counted as unknown`() {
        // RPC.publish (field 2) encoded as a varint rather than length-delimited. The wire type does
        // not match the schema, so protobuf-java cannot parse it as publish and retains each
        // occurrence as an unknown field instead.
        val frame = frameOf(occurrences * 2) { out ->
            repeat(occurrences) {
                out.write(0x10)
                out.write(0x01)
            }
        }
        assertBoundedOnlyByFieldBudget("mismatched wire type", frame)
    }

    @Test
    fun `nested unknown groups are counted through their interior`() {
        // One unknown group (field 14, START_GROUP) holding many field-15 varints. Groups are the
        // one unknown shape protobuf-java expands field by field, so a walker that skipped the group
        // whole would never see the interior.
        val frame = frameOf(occurrences * 2 + 2) { out ->
            out.write(0x73)
            repeat(occurrences) {
                out.write(0x78)
                out.write(0x01)
            }
            out.write(0x74)
        }
        assertBoundedOnlyByFieldBudget("nested unknown group", frame)
    }

    @Test
    fun `a repeated singular field is counted per occurrence`() {
        // RPC.control (field 3) is singular in the schema but repeats on the wire, each with an
        // empty body. Protobuf merges them into one message, so the count is what bounds this.
        val frame = frameOf(occurrences * 2) { out ->
            repeat(occurrences) {
                out.write(0x1a)
                out.write(0x00)
            }
        }
        assertBoundedOnlyByFieldBudget("repeated singular control", frame)
    }

    @Test
    fun `a repeated singular field nested in a publish entry is counted per occurrence`() {
        // The same shape one level down: one publish entry wrapping many occurrences of the singular
        // Message.data field. Publish payload bytes are exempt from the control byte budget, so the
        // field budget is the only limit with visibility into this.
        val body = frameOf(occurrences * 3) { out ->
            repeat(occurrences) {
                out.write(0x12)
                out.write(0x01)
                out.write(0x41)
            }
        }
        val frame = frameOf(body.size + 8) { out ->
            out.write(0x12)
            out.varint(body.size)
            out.write(body)
        }
        assertBoundedOnlyByFieldBudget("repeated singular data in publish", frame)
    }
}
