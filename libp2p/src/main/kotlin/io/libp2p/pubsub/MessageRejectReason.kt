package io.libp2p.pubsub

/**
 * Why a first-seen message was rejected.
 *
 * The router already distinguishes these two cases internally; carrying the distinction out to
 * listeners lets them be counted separately, which is what tells a wire-level problem (a peer
 * sending malformed or badly signed messages) apart from an application-level one (a peer relaying
 * messages this node considers invalid).
 */
enum class MessageRejectReason {

    /**
     * The [PubsubMessageValidator] threw: wire format, signature or field checks failed. The sender
     * is at fault.
     */
    ValidationFailed,

    /**
     * The application message handler returned [io.libp2p.core.pubsub.ValidationResult.Invalid].
     * The message was well formed but the application judged its contents invalid.
     */
    RejectedByHandler
}
