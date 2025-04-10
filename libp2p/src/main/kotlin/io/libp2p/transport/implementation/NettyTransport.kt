package io.libp2p.transport.implementation

import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.transport.Transport
import io.netty.channel.Channel

interface NettyTransport : Transport {

    fun localAddress(nettyChannel: Channel): Multiaddr

    fun remoteAddress(nettyChannel: Channel): Multiaddr
}
