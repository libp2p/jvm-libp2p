package io.libp2p.etc.util.netty

import io.netty.channel.Channel
import io.netty.channel.DefaultChannelPipeline

// TODO experimental
class CachingChannelPipeline(channel: Channel) : DefaultChannelPipeline(channel) {

    enum class EventType { Message, Exception, UserEvent, Active, Inactive }
    class Event(val type: EventType, val data: Any?)

    override fun onUnhandledInboundMessage(msg: Any?) {
        super.onUnhandledInboundMessage(msg)
    }

    override fun onUnhandledInboundChannelReadComplete() {
        super.onUnhandledInboundChannelReadComplete()
    }

    override fun onUnhandledInboundUserEventTriggered(evt: Any?) {
        super.onUnhandledInboundUserEventTriggered(evt)
    }

    override fun onUnhandledInboundException(cause: Throwable?) {
        super.onUnhandledInboundException(cause)
    }

    override fun onUnhandledChannelWritabilityChanged() {
        super.onUnhandledChannelWritabilityChanged()
    }

    override fun onUnhandledInboundChannelActive() {
        super.onUnhandledInboundChannelActive()
    }

    override fun onUnhandledInboundChannelInactive() {
        super.onUnhandledInboundChannelInactive()
    }
}
