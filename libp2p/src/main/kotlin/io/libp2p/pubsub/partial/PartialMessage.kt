package io.libp2p.pubsub.partial

import io.libp2p.core.PeerId

/**
 * Interface for partial message support in gossipsub.
 * Applications implement this to define message splitting behavior.
 */
interface PartialMessage {
    /**
     * Returns unique identifier for this message group.
     * All partial messages with the same groupId belong to the same logical message.
     * For PeerDAS: this is the block root.
     *
     * @return the group identifier as a byte array
     */
    fun groupId(): ByteArray

    /**
     * Returns metadata describing which parts are currently available.
     * Encoding is application-defined (e.g., bitmap for cell presence).
     *
     * @return the parts metadata as a byte array
     */
    fun partsMetadata(): ByteArray

    /**
     * Generates partial message bytes based on requested metadata.
     *
     * @param requestedMetadata Metadata from peer indicating what parts they have/want.
     *                          null if peer hasn't sent metadata yet.
     * @return Action describing what to send and whether we need more parts.
     */
    fun partialMessageBytes(requestedMetadata: ByteArray?): PartialPublishAction
}

/**
 * Result of generating partial message bytes.
 *
 * @property needMoreParts True if we need more parts from the remote peer.
 * @property messageToSend Message bytes to send, or null if nothing to send.
 * @property updatedMetadata Updated metadata reflecting what we're sending.
 */
data class PartialPublishAction(
    val needMoreParts: Boolean,
    val messageToSend: ByteArray?,
    val updatedMetadata: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PartialPublishAction) return false
        return needMoreParts == other.needMoreParts &&
            messageToSend.contentEquals(other.messageToSend) &&
            updatedMetadata.contentEquals(other.updatedMetadata)
    }

    override fun hashCode(): Int {
        var result = needMoreParts.hashCode()
        result = 31 * result + (messageToSend?.contentHashCode() ?: 0)
        result = 31 * result + (updatedMetadata?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Options for publishing partial messages.
 *
 * @property eagerPush Optional eager data to push to peers before they send metadata.
 * @property targetPeers Optional list of specific peers to publish to.
 */
data class PartialPublishOptions(
    val eagerPush: ByteArray? = null,
    val targetPeers: List<PeerId>? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PartialPublishOptions) return false
        return eagerPush.contentEquals(other.eagerPush) && targetPeers == other.targetPeers
    }

    override fun hashCode(): Int {
        var result = eagerPush?.contentHashCode() ?: 0
        result = 31 * result + (targetPeers?.hashCode() ?: 0)
        return result
    }
}
