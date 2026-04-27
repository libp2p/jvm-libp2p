package io.libp2p.pubsub.gossip.partialmessages

import io.libp2p.core.PeerId
import io.libp2p.pubsub.Topic

enum class FeedbackKind { USEFUL, INVALID, IGNORED }

interface PartialMessagesPeerFeedback {
    fun reportFeedback(topic: Topic, peer: PeerId, kind: FeedbackKind)
}

internal object NopPartialMessagesFeedback : PartialMessagesPeerFeedback {
    override fun reportFeedback(topic: Topic, peer: PeerId, kind: FeedbackKind) {}
}
