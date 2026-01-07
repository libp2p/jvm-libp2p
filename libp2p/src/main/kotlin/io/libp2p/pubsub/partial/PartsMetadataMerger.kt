package io.libp2p.pubsub.partial

/**
 * Interface for merging parts metadata.
 * Applications implement this to define how metadata from different sources is combined.
 */
fun interface PartsMetadataMerger {
    /**
     * Merges two metadata values.
     * Used when receiving metadata updates from peers.
     *
     * @param existing Current metadata (may be null if none exists)
     * @param incoming New metadata from peer
     * @return Merged metadata
     */
    fun merge(existing: ByteArray?, incoming: ByteArray): ByteArray
}

/**
 * Default merger that uses bitwise OR (union) semantics.
 * Suitable for bitmap-based metadata where each bit represents a part.
 */
object BitwiseOrMerger : PartsMetadataMerger {
    override fun merge(existing: ByteArray?, incoming: ByteArray): ByteArray {
        if (existing == null) return incoming.copyOf()

        val result = ByteArray(maxOf(existing.size, incoming.size))
        for (i in result.indices) {
            val e = if (i < existing.size) existing[i] else 0
            val n = if (i < incoming.size) incoming[i] else 0
            result[i] = (e.toInt() or n.toInt()).toByte()
        }
        return result
    }
}
