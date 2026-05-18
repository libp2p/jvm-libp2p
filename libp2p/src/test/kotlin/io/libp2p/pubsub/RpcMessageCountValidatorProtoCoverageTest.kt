package io.libp2p.pubsub

import com.google.protobuf.Descriptors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc

/**
 * Bit-rot guard: any new repeated proto field added to `rpc.proto` must be
 * explicitly acknowledged in [RpcMessageCountValidator.ACKNOWLEDGED_REPEATED_FIELDS],
 * forcing the author to consider whether it needs a count limit.
 *
 * Walks every message descriptor reachable from [Rpc.RPC] and compares the
 * repeated-field set against the validator's acknowledged set per descriptor.
 */
class RpcMessageCountValidatorProtoCoverageTest {

    @Test
    fun `every repeated field reachable from RPC is acknowledged by the validator`() {
        val reachable = reachableDescriptors(Rpc.RPC.getDescriptor())
        val expected: Map<Descriptors.Descriptor, Set<Int>> = reachable
            .associateWith { d -> d.fields.filter { it.isRepeated }.map { it.number }.toSet() }
            .filterValues { it.isNotEmpty() }

        val actual = RpcMessageCountValidator.ACKNOWLEDGED_REPEATED_FIELDS

        expected.forEach { (descriptor, expectedFields) ->
            val actualFields = actual[descriptor] ?: emptySet()
            assertThat(actualFields)
                .describedAs(
                    "Repeated fields in %s must be acknowledged in " +
                        "RpcMessageCountValidator.ACKNOWLEDGED_REPEATED_FIELDS. " +
                        "Add new fields (and the corresponding decode-time guard) " +
                        "before merging.",
                    descriptor.fullName,
                )
                .isEqualTo(expectedFields)
        }

        val stale = actual.keys - expected.keys
        assertThat(stale)
            .describedAs(
                "Stale entries in ACKNOWLEDGED_REPEATED_FIELDS — these descriptors " +
                    "are no longer reachable from Rpc.RPC or no longer contain repeated " +
                    "fields: %s",
                stale.map { it.fullName },
            )
            .isEmpty()
    }

    private fun reachableDescriptors(root: Descriptors.Descriptor): Set<Descriptors.Descriptor> {
        val seen = LinkedHashSet<Descriptors.Descriptor>()
        val stack: MutableList<Descriptors.Descriptor> = mutableListOf(root)
        while (stack.isNotEmpty()) {
            val descriptor = stack.removeAt(stack.lastIndex)
            if (!seen.add(descriptor)) continue
            descriptor.fields.forEach { field ->
                if (field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
                    stack.add(field.messageType)
                }
            }
        }
        return seen
    }
}
